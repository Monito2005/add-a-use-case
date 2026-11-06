// UC-STU-deactivate-student T01/T04/T06/T12/T16. Student APIs are stubbed;
// login is real. Real access revocation has a separate live test.
const active = { id: 4, username: 'j.smith@abc.edu', firstName: 'John', lastName: 'Smith', email: 'j.smith@abc.edu', enabled: true, teamId: 1, teamName: 'Team1', sectionId: 2 }
const inactive = { ...active, enabled: false, teamId: null, teamName: null }
const result = (data, message = 'Find student successfully') => ({ flag: true, code: 200, message, data })
const page = (student) => result({ content: [student], number: 0, size: 50, totalElements: 1 })

describe('Student deactivation dialog (stubbed student APIs)', () => {
  let student
  beforeEach(() => {
    student = { ...active }
    cy.login('b.gates@abc.edu', '123456')
    cy.intercept('POST', '**/students/search*', req => req.reply(page(student))).as('students')
    cy.intercept('GET', '**/students/4', req => req.reply(result(student))).as('student')
    cy.intercept('POST', '**/students/4/deactivate', { delay: 800, body: result(inactive, 'Student deactivated successfully') }).as('deactivate')
  })
  function open() {
    cy.visit('/students')
    cy.wait('@students')
    cy.get('[data-cy=student-deactivate]').click()
    cy.get('[data-cy=deactivate-dialog]').should('be.visible')
  }
  it('warns of every consequence and confirms once while pending (T01)', () => {
    cy.intercept('POST', '**/students/4/deactivate', req => {
      student = { ...inactive }
      req.reply({ delay: 800, body: result(student, 'Student deactivated successfully') })
    }).as('deactivate')
    open()
    cy.get('[data-cy=deactivate-dialog]').should('contain', 'John Smith')
    for (const text of ['existing login', 'team assignment', 'enrollment', 'reminders', 'earlier weeks', 'weekly activity reports', 'historical reports', 'separate course-admin team assignment']) {
      cy.get('[data-cy=deactivate-consequences]').should('contain', text)
    }
    cy.get('[data-cy=deactivate-dialog]').screenshot('confirmation-dialog')
    cy.get('[data-cy=deactivate-confirm]').click().should('be.disabled')
    cy.get('[data-cy=deactivate-confirm]').click({ force: true })
    cy.wait('@deactivate')
    cy.wait('@students')
    cy.contains('Student deactivated successfully').should('be.visible')
    cy.get('[data-cy=student-status]').should('contain', 'Inactive').and('have.class', 'el-tag--danger')
    cy.get('[data-cy=student-deactivate]').should('be.disabled')
    cy.contains('td', 'Team1').should('not.exist')
    cy.get('@deactivate.all').should('have.length', 1)
  })
  for (const dismissal of ['cancel', 'close', 'escape', 'backdrop']) {
    it(`sends no request on ${dismissal} (T06)`, () => {
      open()
      if (dismissal === 'cancel') cy.get('[data-cy=deactivate-cancel]').click()
      if (dismissal === 'close') cy.get('[data-cy=deactivate-dialog] .el-dialog__headerbtn').click()
      if (dismissal === 'escape') cy.get('body').type('{esc}')
      if (dismissal === 'backdrop') cy.get('.el-overlay-dialog').last().click('topLeft', { force: true })
      cy.get('[data-cy=deactivate-dialog]').should('not.be.visible')
      cy.get('@deactivate.all').should('have.length', 0)
      cy.get('[data-cy=student-status]').should('contain', 'Active').and('have.class', 'el-tag--success')
      cy.contains('td', 'Team1').should('exist')
    })
  }
  it('refreshes a stale confirmation with the exact already-deactivated message (T04)', () => {
    cy.intercept('POST', '**/students/4/deactivate', req => {
      student = { ...inactive }
      req.reply(result(student, 'Student is already deactivated'))
    }).as('deactivate')
    open()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@deactivate')
    cy.wait('@students')
    cy.contains('Student is already deactivated').should('be.visible')
    cy.get('[data-cy=student-status]').should('contain', 'Inactive')
  })
  it('shows selected-details status beside the name and removes the team after success (T12)', () => {
    cy.intercept('POST', '**/students/4/deactivate', req => { student = { ...inactive }; req.reply(result(student, 'Student deactivated successfully')) }).as('deactivate')
    cy.visit('/students/4')
    cy.wait('@student')
    cy.get('[data-cy=student-details]').should('contain', 'John Smith').and('contain', 'Active')
    cy.get('[data-cy=student-deactivate]').click()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@deactivate')
    cy.get('[data-cy=student-details] [data-cy=student-status]').should('contain', 'Inactive').and('have.class', 'el-tag--danger')
    cy.get('[data-cy=student-team]').should('contain', 'None')
    cy.get('[data-cy=student-deactivate]').should('be.disabled')
  })
  it('keeps profile status read-only (T12)', () => {
    cy.visit('/students')
    cy.wait('@students')
    cy.get('[data-cy=student-edit]').click()
    cy.wait('@student')
    cy.get('[data-cy=profile-status]').should('contain', 'Active')
    cy.get('.el-dialog .el-switch').should('not.exist')
  })
  it('refreshes status after a network failure before another action (T16)', () => {
    cy.intercept('POST', '**/students/4/deactivate', { forceNetworkError: true }).as('deactivate')
    cy.intercept('GET', '**/students/4', { delay: 1200, body: result(active) }).as('refresh')
    open()
    cy.get('[data-cy=deactivate-confirm]').click().should('be.disabled')
    cy.wait('@deactivate')
    cy.get('[data-cy=deactivate-notice]').should('contain', 'unknown')
    cy.wait('@refresh')
    cy.get('[data-cy=deactivate-notice]').should('contain', 'Status refreshed: Active')
    cy.get('[data-cy=deactivate-confirm]').should('be.enabled')
    cy.contains('Student deactivated successfully').should('not.exist')
    cy.get('[data-cy=student-status]').should('contain', 'Active')
  })
  it('treats a gateway timeout as unknown and reloads the committed status (T16)', () => {
    cy.intercept('POST', '**/students/4/deactivate', { statusCode: 504, body: { flag: false, message: 'Gateway timeout' } }).as('deactivate')
    cy.intercept('GET', '**/students/4', { delay: 700, body: result(inactive) }).as('refresh')
    open()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@refresh')
    cy.get('[data-cy=student-status]').should('contain', 'Inactive')
    cy.get('[data-cy=deactivate-confirm]').should('be.disabled')
    cy.contains('Student deactivated successfully').should('not.exist')
  })
  it('keeps confirmation disabled if the status refresh also fails (T16)', () => {
    cy.intercept('POST', '**/students/4/deactivate', { forceNetworkError: true }).as('deactivate')
    cy.intercept('GET', '**/students/4', { forceNetworkError: true }).as('refresh')
    open()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@refresh')
    cy.get('[data-cy=deactivate-confirm]').should('be.disabled')
    cy.get('[data-cy=deactivate-notice]').should('contain', 'unknown')
    cy.get('[data-cy=deactivate-cancel]').click()
    cy.get('[data-cy=student-deactivate]').click()
    cy.wait('@refresh')
    cy.get('[data-cy=deactivate-confirm]').should('be.disabled')
    cy.intercept('GET', '**/students/4', { body: result(inactive) }).as('retryRefresh')
    cy.get('[data-cy=deactivate-refresh]').click()
    cy.wait('@retryRefresh')
    cy.get('[data-cy=student-status]').should('contain', 'Inactive')
    cy.get('[data-cy=deactivate-confirm]').should('be.disabled')
    cy.get('@deactivate.all').should('have.length', 1)
  })
  it('uses the shared forbidden handling without claiming success (T16)', () => {
    cy.intercept('POST', '**/students/4/deactivate', { statusCode: 403, body: { flag: false, code: 403, message: 'No permission.' } }).as('deactivate')
    open()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@deactivate')
    cy.url().should('include', '/403')
    cy.contains('Student deactivated successfully').should('not.exist')
  })
})
