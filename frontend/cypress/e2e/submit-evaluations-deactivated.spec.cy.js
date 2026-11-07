// UC-EVA-submit-evaluation with a deactivated teammate (BR-no-evaluation-of-inactive, docs/design/eva.md): who the
// submit page lists, what each save sends, and what it does after a 9a rejection. The evaluation, student, section,
// and rubric APIs are stubbed; the backend's own tests cover the rule itself. Run against the dev stack (backend +
// `npm run dev`) with `npm run cy:open`.

const criteria = [1, 2, 3, 4, 5, 6].map((id) => ({
  criterionId: id,
  criterion: `Criterion ${id}`,
  description: `C${id}`,
  maxScore: 10
}))

const teammate = (id, firstName, lastName, enabled) => ({
  id,
  username: `${firstName.toLowerCase()}@abc.edu`,
  firstName,
  lastName,
  email: `${firstName.toLowerCase()}@abc.edu`,
  enabled,
  teamId: 1,
  teamName: 'Team1'
})

const saved = (evaluationId, evaluateeId, evaluateeName) => ({
  evaluationId,
  week: 'stubbed',
  evaluatorId: 4,
  evaluatorName: 'John Smith',
  evaluateeId,
  evaluateeName,
  ratings: criteria.map((c) => ({ criterionId: c.criterionId, actualScore: 8 })),
  totalScore: 48,
  publicComment: '',
  privateComment: ''
})

const ok = (data) => ({ flag: true, code: 200, message: 'OK', data })

describe('Submit peer evaluations with a deactivated teammate', () => {
  let team // GET /students/teams/1
  let savedEvaluations // GET /evaluations/evaluators/4/week/*

  beforeEach(() => {
    cy.login('j.smith@abc.edu', '123456')
    cy.intercept('GET', '**/api/v1/sections/*', { body: ok({ sectionId: 2, rubricId: 1 }) })
    cy.intercept('GET', '**/api/v1/rubrics/1', { body: ok({ rubricId: 1, criteria }) })
    cy.intercept('GET', '**/api/v1/students/teams/1', (req) => req.reply(ok(team))).as('team')
    cy.intercept('GET', '**/api/v1/evaluations/evaluators/4/week/*', (req) => req.reply(ok(savedEvaluations))).as('saved')
    cy.intercept('POST', '**/api/v1/evaluations/weeks/*/receipt', { body: ok(null) }).as('receipt')
    cy.intercept('PUT', '**/api/v1/evaluations/*', (req) => req.reply(ok({ ...req.body }))).as('put')
  })

  function openPage() {
    cy.visit('/evaluations/submit-evaluations')
    cy.wait(['@team', '@saved'])
  }

  const listed = () => cy.get('.el-table__body-wrapper .el-table__row')

  it('lists active teammates and a deactivated one evaluated earlier, not a deactivated one never evaluated (step 2)', () => {
    team = [
      teammate(4, 'John', 'Smith', true),
      teammate(5, 'Eric', 'Hudson', false), // Evaluated before she was deactivated
      teammate(6, 'Jerry', 'Moon', false) // Deactivated before she was evaluated
    ]
    savedEvaluations = [saved(11, 4, 'John Smith'), saved(12, 5, 'Eric Hudson')]
    openPage()

    listed().should('have.length', 2)
    cy.get('.el-table__body-wrapper').should('contain', 'John Smith').and('contain', 'Eric Hudson').and('not.contain', 'Jerry Moon')

    cy.get('[data-cy=evaluations-update]').click()
    cy.wait(['@put', '@put'])
    cy.get('@put.all').then((puts) => {
      expect(puts.map((p) => p.request.url.split('/').pop()).sort()).to.deep.equal(['11', '12'])
    })
    cy.get('.el-message').should('contain', 'Peer evaluations updated successfully')
    cy.get('@receipt.all').should('have.length', 0) // An Update sends no confirmation
  })

  it('keeps listing a saved evaluation of someone moved off the team, and updates it (BR-evaluation-editable-until-close)', () => {
    team = [teammate(4, 'John', 'Smith', true)]
    savedEvaluations = [saved(11, 4, 'John Smith'), saved(13, 9, 'Woody Allen')] // Woody has since moved teams
    openPage()

    listed().should('have.length', 2)
    cy.get('.el-table__body-wrapper').should('contain', 'Woody Allen')

    cy.get('[data-cy=evaluations-update]').click()
    cy.wait(['@put', '@put'])
    cy.get('@put.all').then((puts) => {
      expect(puts.map((p) => p.request.url.split('/').pop()).sort()).to.deep.equal(['11', '13'])
    })
  })

  it('sends every POST when one is rejected for a deactivated teammate, then drops her (9a)', () => {
    team = [teammate(4, 'John', 'Smith', true), teammate(5, 'Eric', 'Hudson', true), teammate(6, 'Jerry', 'Moon', true)]
    savedEvaluations = []
    let nextId = 20
    cy.intercept('POST', '**/api/v1/evaluations', (req) => {
      if (req.body.evaluateeId === 6) {
        req.reply({
          statusCode: 400,
          body: { flag: false, code: 400, message: "This teammate's account is deactivated and can no longer be evaluated." }
        })
        return
      }
      const evaluation = { ...req.body, evaluationId: nextId++ }
      savedEvaluations.push(evaluation)
      req.reply(ok(evaluation))
    }).as('post')
    openPage()

    // Jerry is deactivated after the page loaded
    cy.then(() => {
      team = [team[0], team[1], teammate(6, 'Jerry', 'Moon', false)]
    })
    cy.get('[data-cy=evaluations-submit]').click()

    cy.wait(['@post', '@post', '@post'])
    cy.get('@post.all').should('have.length', 3)
    cy.get('.el-message').should('contain', "This teammate's account is deactivated and can no longer be evaluated.")
    cy.wait('@team') // The team is re-read
    listed().should('have.length', 2)
    cy.get('.el-table__body-wrapper').should('not.contain', 'Jerry Moon')
    // Every evaluation still listed is saved, so the submission is complete and is confirmed
    cy.get('.el-message').should('contain', 'Peer evaluations submitted successfully')
    cy.wait('@receipt')

    cy.get('[data-cy=evaluations-update]').click()
    cy.wait(['@put', '@put'])
    cy.get('@put.all').should('have.length', 2)
    cy.get('@post.all').should('have.length', 3) // No further POST
    cy.get('@receipt.all').should('have.length', 1) // and no second confirmation
  })

  it('confirms the submission on the retry after a failure that leaves an evaluation unsaved', () => {
    team = [teammate(4, 'John', 'Smith', true), teammate(5, 'Eric', 'Hudson', true)]
    savedEvaluations = []
    let failEric = true
    cy.intercept('POST', '**/api/v1/evaluations', (req) => {
      if (req.body.evaluateeId === 5 && failEric) {
        failEric = false
        req.reply({ statusCode: 500, body: { flag: false, code: 500, message: 'Something went wrong.' } })
        return
      }
      req.reply(ok({ ...req.body, evaluationId: 50 + req.body.evaluateeId }))
    }).as('post')
    openPage()

    cy.get('[data-cy=evaluations-submit]').click()
    cy.wait(['@post', '@post'])
    cy.get('.el-message').should('contain', 'Something went wrong.')
    cy.get('@receipt.all').should('have.length', 0) // Not complete yet

    cy.get('[data-cy=evaluations-submit]').click()
    cy.wait(['@put', '@post'])
    cy.get('.el-message').should('contain', 'Peer evaluations submitted successfully')
    cy.wait('@receipt')
    cy.get('@receipt.all').should('have.length', 1)
  })

  it('keeps what the student typed for an evaluation whose save failed', () => {
    team = [teammate(4, 'John', 'Smith', true), teammate(5, 'Eric', 'Hudson', true)]
    savedEvaluations = []
    cy.intercept('POST', '**/api/v1/evaluations', (req) => {
      if (req.body.evaluateeId === 5) {
        req.reply({ statusCode: 500, body: { flag: false, code: 500, message: 'Something went wrong.' } })
        return
      }
      const evaluation = { ...req.body, evaluationId: 40 }
      savedEvaluations.push(evaluation)
      req.reply(ok(evaluation))
    }).as('post')
    openPage()

    cy.contains('.el-table__row', 'Eric Hudson').find('textarea').first().type('Eric carried the sprint demo.')
    cy.get('[data-cy=evaluations-submit]').click()

    cy.wait(['@post', '@post'])
    cy.get('.el-message').should('contain', 'Something went wrong.')
    cy.contains('.el-table__row', 'Eric Hudson').find('textarea').first().should('have.value', 'Eric carried the sprint demo.')
    cy.get('[data-cy=evaluations-submit]').should('exist') // Eric's evaluation is still unsaved
  })

  it('sends a PUT for a saved evaluation and a POST for an unsaved one in the same save (partial save)', () => {
    team = [teammate(4, 'John', 'Smith', true), teammate(5, 'Eric', 'Hudson', true)]
    savedEvaluations = [saved(11, 4, 'John Smith')]
    cy.intercept('POST', '**/api/v1/evaluations', (req) => req.reply(ok({ ...req.body, evaluationId: 30 }))).as('post')
    openPage()

    cy.get('[data-cy=evaluations-submit]').click()

    cy.wait('@put').its('request.url').should('match', /\/evaluations\/11$/)
    cy.wait('@post').its('request.body.evaluateeId').should('eq', 5)
    cy.get('.el-message').should('contain', 'Peer evaluations submitted successfully')
    cy.wait('@receipt') // A Submit sends the confirmation
    cy.get('[data-cy=evaluations-update]').click()
    cy.wait(['@put', '@put'])
    cy.get('@receipt.all').should('have.length', 1) // and the Update after it does not send another
  })
})
