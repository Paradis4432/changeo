const { firefox } = require('../.local-tools/browser/node_modules/playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const logs = path.join(root, 'docs/agent-work/changeo-moderation/developer/logs');
const runtime = path.join(root, '.runtime');
const base = 'http://127.0.0.1:8080';
const checks = {};

function totp(secret) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let buffer = 0, bits = 0;
  const bytes = [];
  for (const character of secret) {
    buffer = (buffer << 5) | alphabet.indexOf(character); bits += 5;
    if (bits >= 8) { bits -= 8; bytes.push((buffer >> bits) & 255); }
  }
  const counter = Buffer.alloc(8); counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const digest = crypto.createHmac('sha1', Buffer.from(bytes)).update(counter).digest();
  return String((digest.readUInt32BE(digest[digest.length - 1] & 15) & 0x7fffffff) % 1000000).padStart(6, '0');
}
async function login(page, contact, password) {
  await page.goto(base + '/login'); await page.getByLabel('Correo sintético').fill(contact);
  await page.getByLabel('Contraseña', { exact: true }).fill(password); await page.getByRole('button', { name: 'Ingresar', exact: true }).click(); await page.waitForURL('**/account');
}
async function status(page, resource, expected) {
  for (let count = 0; count < 80; count++) {
    await page.goto(base + '/moderation/own/' + resource);
    if ((await page.locator('[role=status]').allTextContents()).some(text => text.includes(expected))) return;
    await page.waitForTimeout(500);
  }
  throw new Error('Expected moderation status missing');
}
async function published(page, resource) {
  for (let count = 0; count < 40; count++) {
    const response = await page.goto(base + '/content/' + resource);
    if (response.status() === 200) return;
    await page.waitForTimeout(500);
  }
  throw new Error('Protected activation not completed');
}
async function save(page, text, upload = false) {
  await page.goto(base + '/moderation'); await page.getByLabel('Texto', { exact: true }).fill(text);
  if (upload) await page.getByLabel('Adjunto opcional (1 MiB máximo local)').setInputFiles({ name: 'fixture.txt', mimeType: 'text/plain', buffer: Buffer.from('synthetic:clear') });
  await page.getByRole('button', { name: 'Guardar borrador', exact: true }).click(); await page.waitForURL('**/moderation/own/*');
  const resource = page.url().split('/').pop();
  assert.ok((await page.locator('[role=status]').allTextContents()).some(text => text.includes('Borrador guardado; falta enviar')));
  await page.getByRole('button', { name: 'Enviar a revisión', exact: true }).click(); return resource;
}
async function run() {
  const browser = await firefox.launch({ executablePath: '/home/paradis/.cache/ms-playwright/firefox-1553/firefox/firefox', headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, acceptDownloads: true });
  const operatorContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage(), operator = await operatorContext.newPage();
  page.on('pageerror', () => { checks.pageError = true; }); operator.on('pageerror', () => { checks.pageError = true; });
  try {
    if (process.argv.includes('--restart-check')) {
      const fixture = JSON.parse(await fs.readFile(path.join(runtime, 'm1-browser-fixture.private'), 'utf8'));
      await login(page, fixture.contact, fixture.password);
      await published(page, fixture.clear); assert.equal(await page.locator('pre').innerText(), 'synthetic:clear');
      const downloadPromise = page.waitForEvent('download'); await page.getByRole('link', { name: 'Descargar adjunto aprobado' }).click();
      const download = await downloadPromise; assert.equal(await fs.readFile(await download.path(), 'utf8'), 'synthetic:clear');
      await status(page, fixture.doubt, 'Revisión aprobada'); checks.persistedApprovalFilesAfterRestart = true;
      await fs.writeFile(path.join(logs, 'browser-restart.json'), JSON.stringify(checks, null, 2));
      if (process.argv.includes('--visual-check')) {
        await page.goto(base + '/moderation/own/' + fixture.clear);
        await page.getByRole('link', { name: 'Taller', exact: true }).focus(); await page.keyboard.press('Tab');
        assert.equal(await page.evaluate(() => getComputedStyle(document.activeElement).outlineWidth), '3px'); checks.visibleKeyboardFocus = true;
        assert.equal((await context.request.post(base + '/moderation/submit', { form: { resource: fixture.clear, revision: crypto.randomUUID() } })).status(), 403); checks.missingCsrfDenied = true;
        await page.screenshot({ path: path.join(logs, 'browser-own-desktop.png'), fullPage: true });
        await page.setViewportSize({ width: 390, height: 844 }); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); await page.screenshot({ path: path.join(logs, 'browser-own-final-mobile.png'), fullPage: true });
        const material = Object.fromEntries((await fs.readFile(path.join(runtime, 'm1-browser-enrollment.private'), 'utf8')).split('\n').filter(line => line.includes('=')).map(line => { const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)]; }));
        await login(operator, material.contact, material.password); await operator.goto(base + '/moderation/operator/signin'); await operator.getByLabel('Código MFA del factor privado').fill(totp(material.totpBase32)); await operator.getByRole('button', { name: 'Verificar MFA' }).click();
        await operator.getByLabel('Contraseña reciente (necesaria para revisión)').fill(material.password); await operator.getByRole('button', { name: 'Confirmar y abrir revisión' }).click(); await operator.waitForURL('**/moderation/operator');
        await operator.screenshot({ path: path.join(logs, 'browser-operator-desktop.png'), fullPage: true }); await operator.setViewportSize({ width: 390, height: 844 }); assert.equal(await operator.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); await operator.screenshot({ path: path.join(logs, 'browser-operator-final-mobile.png'), fullPage: true });
        await fs.writeFile(path.join(logs, 'browser-final-visual.json'), JSON.stringify(checks, null, 2));
      }
      return;
    }
    const fixture = { contact: 'm1qa' + crypto.randomUUID() + '@example.test', password: crypto.randomBytes(24).toString('base64url') };
    await page.goto(base + '/register'); await page.getByLabel('Correo sintético').fill(fixture.contact); await page.getByLabel('Contraseña (12 a 72 caracteres)').fill(fixture.password);
    await page.getByLabel('Correo sintético').focus(); await page.keyboard.press('Tab'); assert.equal(await page.locator('#password').evaluate(element => element === document.activeElement), true);
    await page.keyboard.press('Tab'); await page.keyboard.press('Enter'); await page.waitForURL('**/login'); await login(page, fixture.contact, fixture.password);
    const id = await page.locator('main > p code').innerText();
    const tokenFile = (await fs.readdir(path.join(runtime, 'mailbox'))).find(name => name.startsWith(id + '-CONTACT-'));
    await page.getByLabel('Código privado').fill((await fs.readFile(path.join(runtime, 'mailbox', tokenFile), 'utf8')).trim()); await page.getByRole('button', { name: 'Verificar', exact: true }).click(); await page.waitForURL('**/account');
    checks.keyboardRegistrationVerification = true;
    const material = Object.fromEntries((await fs.readFile(path.join(runtime, 'm1-browser-enrollment.private'), 'utf8')).split('\n').filter(line => line.includes('=')).map(line => { const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)]; }));
    await login(operator, material.contact, material.password);
    assert.equal((await operator.goto(base + '/moderation/operator')).status(), 403); checks.passwordOnlyDenied = true;
    await operator.goto(base + '/moderation/operator/signin'); await operator.getByLabel('Código MFA del factor privado').fill(totp(material.totpBase32)); await operator.getByRole('button', { name: 'Verificar MFA' }).click();
    await operator.getByLabel('Contraseña reciente (necesaria para revisión)').fill(material.password); await operator.getByRole('button', { name: 'Confirmar y abrir revisión' }).click(); await operator.waitForURL('**/moderation/operator');
    await operator.goto(base + '/admin/accounts'); const account = operator.locator('section.card').filter({ has: operator.getByRole('heading', { name: id, exact: true }) });
    await account.getByLabel('Edad de prueba (solo evidencia de edad)').fill('30'); await account.getByRole('button', { name: 'Registrar evidencia sintética' }).click(); checks.currentIdentityEvidence = true;
    fixture.clear = await save(page, 'synthetic:clear', true); await status(page, fixture.clear, 'Revisión aprobada');
    await published(page, fixture.clear); assert.equal(await page.locator('pre').innerText(), 'synthetic:clear');
    const downloadPromise = page.waitForEvent('download'); await page.getByRole('link', { name: 'Descargar adjunto aprobado' }).click(); const download = await downloadPromise; assert.equal(await fs.readFile(await download.path(), 'utf8'), 'synthetic:clear'); checks.exactApprovedFile = true;
    await page.getByText('Reportar este contenido', { exact: true }).click(); await page.getByRole('button', { name: 'Enviar reporte' }).click(); await page.waitForURL('**/moderation/cases'); checks.reportCreated = true;
    fixture.doubt = await save(page, '<script>fixture</script>'); await status(page, fixture.doubt, 'Esperando revisión humana');
    await page.getByText('Apelar o pedir revisión', { exact: true }).click(); await page.getByRole('button', { name: 'Abrir apelación' }).click(); await page.waitForURL('**/moderation/cases');
    const appeal = page.locator('main section').filter({ has: page.getByRole('heading', { name: 'Apelación', exact: true }) }); const caseId = await appeal.locator('.identifier').innerText(); checks.appealCreated = true;
    checks.stage = 'operatorTakeAppeal'; await operator.goto(base + '/moderation/operator'); let caseArticle = operator.locator('article').filter({ has: operator.locator('.identifier', { hasText: caseId }) });
    await caseArticle.getByText('Revisar acciones del caso', { exact: true }).click(); await caseArticle.getByLabel('Acción explícita').selectOption('TAKE'); await caseArticle.getByRole('button', { name: 'Registrar decisión' }).click();
    checks.stage = 'operatorApproveAppeal'; caseArticle = operator.locator('article').filter({ has: operator.locator('.identifier', { hasText: caseId }) }); await caseArticle.getByText('Revisar acciones del caso', { exact: true }).click(); await caseArticle.getByLabel('Acción explícita').selectOption('APPROVE'); await caseArticle.getByRole('button', { name: 'Registrar decisión' }).click();
    checks.stage = 'authorApprovedAppeal'; await status(page, fixture.doubt, 'Revisión aprobada'); await published(page, fixture.doubt); assert.equal(await page.locator('pre').innerText(), '<script>fixture</script>'); assert.equal(await page.locator('script').count(), 0); checks.humanAppealExactEscaped = true;
    fixture.outage = await save(page, 'synthetic:outage'); await status(page, fixture.outage, 'Reintentos agotados'); await page.getByRole('button', { name: 'Solicitar otro intento' }).click();
    await page.waitForURL('**/moderation/own/*'); assert.equal(await page.getByText('Nuevo intento técnico registrado.', { exact: true }).count(), 1); checks.exhaustionRetry = true;
    await page.goto(base + '/moderation/own/' + fixture.clear); await page.getByLabel('Nuevo texto').fill('synthetic:doubt'); await page.getByRole('button', { name: 'Guardar edición' }).click();
    await published(page, fixture.clear); assert.equal(await page.locator('pre').innerText(), 'synthetic:clear'); checks.retainedApprovedEdit = true;
    for (const route of ['/moderation', '/moderation/own/' + fixture.clear, '/moderation/cases', '/content/' + fixture.doubt, '/content']) {
      await page.setViewportSize({ width: 390, height: 844 }); await page.goto(base + route); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    }
    await page.goto(base + '/moderation/own/' + fixture.clear); await page.screenshot({ path: path.join(logs, 'browser-own-mobile.png'), fullPage: true }); await fs.writeFile(path.join(logs, 'browser-own-accessibility.yml'), await page.locator('body').ariaSnapshot());
    await operator.goto(base + '/moderation/operator'); await operator.setViewportSize({ width: 390, height: 844 }); assert.equal(await operator.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); await operator.screenshot({ path: path.join(logs, 'browser-operator-mobile.png'), fullPage: true });
    checks.mobileNoOverflow = true; assert.equal(checks.pageError, undefined);
    await fs.writeFile(path.join(runtime, 'm1-browser-fixture.private'), JSON.stringify(fixture), { mode: 0o600 });
    await fs.writeFile(path.join(logs, 'browser-qa.json'), JSON.stringify(checks, null, 2)); console.log(JSON.stringify(checks));
  } finally { await context.close(); await operatorContext.close(); await browser.close(); }
}
run().catch(async error => { checks.failure = error.name; checks.frames = (error.stack || '').split('\n').filter(line => line.trim().startsWith('at ')).slice(0, 3); await fs.writeFile(path.join(logs, 'browser-failure.json'), JSON.stringify(checks, null, 2)); console.error(error.name); process.exitCode = 1; });
