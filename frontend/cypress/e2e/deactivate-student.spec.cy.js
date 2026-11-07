// UC-STU-deactivate-student: the Deactivate action on the student's details page, and the Edit dialog's Status
// switch. The student API is stubbed, so these tests check what the page shows and sends; the backend's own tests
// cover who may deactivate and what deactivation changes. Run against the dev stack (backend + `npm run dev`) with
// `npm run cy:open`.

const john = {
  id: 4,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  roles: 'student',
  sectionId: 2,
  sectionName: '2023-2024',
  teamId: 1,
  teamName: 'Team1'
}

const found = (student) => ({ flag: true, code: 200, message: 'Find student successfully', data: student })

const warning =
  'Deactivate John Smith? They will no longer be able to log in or submit weekly activity reports and peer evaluations, ' +
  'and a session they have open ends on its next request. Their account and submitted work are kept, they stay on ' +
  'their team, and they can be reactivated later.'

describe('Deactivate a student', () => {
  let current // What GET /students/4 returns; a test flips it to model the server's state

  beforeEach(() => {
    current = { ...john }
    cy.login('b.gates@abc.edu', '123456')
    cy.intercept('GET', '**/api/v1/students/4', (req) => req.reply(found(current))).as('student')
  })

  function openDetails() {
    cy.visit('/students/4')
    cy.wait('@student')
  }

  it('warns with every consequence, then deactivates on Confirm (steps 4-6)', () => {
    cy.intercept('POST', '**/api/v1/students/4/deactivation', (req) => {
      current = { ...john, enabled: false }
      req.reply({ flag: true, code: 200, message: 'Deactivate student successfully', data: current })
    }).as('deactivate')
    openDetails()
    cy.get('[data-cy=student-status]').should('have.text', 'Active')

    cy.get('[data-cy=student-deactivate]').click()
    cy.get('.el-message-box__message').should('have.text', warning)
    cy.get('.deactivate-confirm').should('contain', 'Confirm')
    cy.get('.deactivate-cancel').should('contain', 'Cancel')
    cy.screenshot('deactivate-student-confirmation', { capture: 'viewport' }) // Evidence for the pull request
    cy.get('.deactivate-confirm').click()

    cy.wait('@deactivate')
    cy.get('.el-message').should('contain', 'Student deactivated.')
    cy.get('[data-cy=student-status]').should('have.text', 'Deactivated')
    cy.get('[data-cy=student-deactivate]').should('not.exist')
    cy.get('@deactivate.all').should('have.length', 1)
  })

  it('sends no request when the user cancels at the warning (5a)', () => {
    cy.intercept('POST', '**/api/v1/students/4/deactivation', { statusCode: 500 }).as('deactivate')
    openDetails()

    cy.get('[data-cy=student-deactivate]').click()
    cy.get('.deactivate-cancel').click()

    cy.get('.el-message-box').should('not.exist')
    cy.get('@deactivate.all').should('have.length', 0)
    cy.get('[data-cy=student-status]').should('have.text', 'Active')
  })

  it('re-fetches the student when she is already deactivated (6a)', () => {
    cy.intercept('POST', '**/api/v1/students/4/deactivation', (req) => {
      current = { ...john, enabled: false } // Someone else deactivated her after the page loaded
      req.reply({ statusCode: 409, body: { flag: false, code: 409, message: 'This student is already deactivated.' } })
    }).as('deactivate')
    openDetails()

    cy.get('[data-cy=student-deactivate]').click()
    cy.get('.deactivate-confirm').click()

    cy.wait('@deactivate')
    cy.get('.el-message').should('contain', 'This student is already deactivated.')
    cy.get('[data-cy=student-status]').should('have.text', 'Deactivated')
    cy.get('[data-cy=student-deactivate]').should('not.exist')
  })

  it('offers Deactivate only for an active student', () => {
    current = { ...john, enabled: false }
    openDetails()

    cy.get('[data-cy=student-status]').should('have.text', 'Deactivated')
    cy.get('[data-cy=student-deactivate]').should('not.exist')
  })
})

describe("The Edit dialog's Status switch", () => {
  const eric = { ...john, id: 5, username: 'e.hudson@abc.edu', firstName: 'Eric', lastName: 'Hudson', email: 'e.hudson@abc.edu', enabled: false }

  beforeEach(() => {
    cy.login('b.gates@abc.edu', '123456')
    cy.intercept('POST', '**/api/v1/students/search*', {
      body: { flag: true, code: 200, message: 'Find students successfully', data: { content: [john, eric], totalElements: 2 } }
    }).as('search')
    cy.intercept('GET', '**/api/v1/students/4', { body: found(john) })
    cy.intercept('GET', '**/api/v1/students/5', { body: found(eric) })
    cy.visit('/students')
    cy.wait('@search')
  })

  it('is hidden for an active student, so the dialog cannot deactivate her', () => {
    cy.contains('tr', 'Smith').find('button').click()
    cy.get('.el-dialog').should('be.visible').and('contain', 'Username')
    cy.get('[data-cy=student-status-switch]').should('not.exist')
  })

  it('is shown for a deactivated student, as the interim reactivate path', () => {
    cy.contains('tr', 'Hudson').find('button').click()
    cy.get('[data-cy=student-status-switch]').should('be.visible')
  })
})
