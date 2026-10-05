const { firefox } = require('../.local-tools/browser/node_modules/playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const logs = path.join(root, 'docs/agent-work/changeo-foundations/developer/logs');
const privateDirectory = path.join(root, '.runtime');
const baseUrl = 'http://127.0.0.1:8080';

async function privateJson(filename, value) {
  await fs.writeFile(path.join(privateDirectory, filename), JSON.stringify(value), { mode: 0o600, flag: 'w' });
}

function totp(base32) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let buffer = 0;
  let bits = 0;
  const bytes = [];
  for (const character of base32) {
    buffer = (buffer << 5) | alphabet.indexOf(character);
    bits += 5;
    if (bits >= 8) { bits -= 8; bytes.push((buffer >> bits) & 255); }
  }
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const digest = crypto.createHmac('sha1', Buffer.from(bytes)).update(counter).digest();
  const offset = digest[digest.length - 1] & 15;
  return String((digest.readUInt32BE(offset) & 0x7fffffff) % 1000000).padStart(6, '0');
}

async function login(page, contact, password) {
  await page.goto(baseUrl + '/login');
  await page.getByLabel('Correo sintético').fill(contact);
  await page.getByLabel('Contraseña', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await page.waitForURL('**/account');
}

async function run() {
  const browser = await firefox.launch({ executablePath: '/home/paradis/.cache/ms-playwright/firefox-1553/firefox/firefox', headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const checks = {};
  page.on('pageerror', () => { checks.pageError = true; });
  try {
    if (process.argv.includes('--restart-check')) {
      const fixture = JSON.parse(await fs.readFile(path.join(privateDirectory, 'browser-fixture.private'), 'utf8'));
      await login(page, fixture.contact, fixture.password);
      assert.equal(await page.getByText('Verificado en el entorno sintético', { exact: true }).count(), 1);
      assert.equal(await page.getByLabel('Ver contenido sensible con advertencia').isChecked(), true);
      checks.persistenceAfterRestart = true;
      await fs.writeFile(path.join(logs, 'browser-restart.json'), JSON.stringify(checks, null, 2));
      return;
    }
    const fixture = { contact: 'qa' + crypto.randomUUID() + '@example.test', password: crypto.randomBytes(24).toString('base64url') };
    await privateJson('browser-fixture.private', fixture);
    await page.goto(baseUrl + '/register');
    await page.getByLabel('Correo sintético').fill(fixture.contact);
    await page.getByLabel('Contraseña (12 a 72 caracteres)').fill(fixture.password);
    await page.getByLabel('Correo sintético').focus();
    await page.keyboard.press('Tab');
    assert.equal(await page.locator('#password').evaluate(element => document.activeElement === element), true);
    await page.keyboard.press('Tab'); await page.keyboard.press('Enter');
    await page.waitForURL('**/login'); checks.keyboardRegistration = true;
    await login(page, fixture.contact, fixture.password); checks.signin = true;
    const ownId = await page.locator('main > p code').innerText();
    const mailbox = path.join(privateDirectory, 'mailbox');
    const tokenFile = (await fs.readdir(mailbox)).find(name => name.startsWith(ownId + '-CONTACT-'));
    const token = (await fs.readFile(path.join(mailbox, tokenFile), 'utf8')).trim();
    await page.getByLabel('Código privado').fill(token);
    await page.getByRole('button', { name: 'Verificar', exact: true }).click();
    await page.waitForURL('**/account');
    assert.equal(await page.getByText('Verificado en el entorno sintético', { exact: true }).count(), 1);
    checks.contactVerification = true;
    await page.getByLabel('Ver contenido sensible con advertencia').check();
    await page.getByRole('button', { name: 'Guardar preferencias' }).click();
    await page.waitForURL('**/account');
    assert.equal(await page.getByLabel('Ver contenido sensible con advertencia').isChecked(), true);
    checks.settingsPersisted = true;
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({ path: path.join(logs, 'browser-account-mobile.png'), fullPage: true });
    checks.mobileNoOverflow = true;
    await fs.writeFile(path.join(logs, 'browser-account-accessibility.yml'), await page.locator('body').ariaSnapshot());
    await page.getByRole('link', { name: 'Guardianes', exact: true }).click();
    assert.equal(await page.getByText('No hay relaciones registradas.', { exact: true }).count(), 1);
    await page.getByRole('link', { name: 'Ayuda', exact: true }).click();
    assert.equal(await page.getByRole('heading', { name: 'Ayuda y derechos', exact: true }).count(), 1);
    checks.guardianAndSupportScreens = true;
    await page.getByRole('button', { name: 'Salir', exact: true }).click();
    const material = Object.fromEntries((await fs.readFile(path.join(privateDirectory, 'operator-enrollment.private'), 'utf8')).split('\n').filter(line => line.includes('=')).map(line => { const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)]; }));
    await login(page, material.contact, material.password);
    const denied = await page.goto(baseUrl + '/admin/accounts'); assert.equal(denied.status(), 403);
    checks.adminPasswordOnlyDenied = true;
    await page.goto(baseUrl + '/admin/mfa');
    const code = totp(material.totpBase32);
    await page.getByLabel('Código de seis dígitos').fill(code);
    await page.getByRole('button', { name: 'Verificar MFA' }).click();
    await page.waitForURL('**/admin/reauth'); checks.adminMfa = true;
    await page.getByLabel('Contraseña actual').fill(material.password);
    await page.getByRole('button', { name: 'Confirmar', exact: true }).click();
    await page.waitForURL('**/admin/accounts'); checks.adminFreshPassword = true;
    await fs.writeFile(path.join(logs, 'browser-admin-accessibility.yml'), await page.locator('body').ariaSnapshot());
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({ path: path.join(logs, 'browser-admin-mobile.png'), fullPage: true });
    checks.adminMobileNoOverflow = true;
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.screenshot({ path: path.join(logs, 'browser-admin-desktop.png'), fullPage: true });
    await page.getByRole('link', { name: 'Auditoría', exact: true }).click();
    assert.equal(await page.getByRole('heading', { name: 'Auditoría de identidad', exact: true }).count(), 1);
    checks.auditScreen = true;
    await page.goto(baseUrl + '/admin/mfa');
    await page.getByLabel('Código de seis dígitos').fill(code);
    await page.getByRole('button', { name: 'Verificar MFA' }).click();
    assert.equal(await page.getByText('Código inválido, vencido o utilizado', { exact: true }).count(), 1);
    checks.adminReplayDenied = true;
    await page.getByRole('button', { name: 'Salir', exact: true }).click();
    checks.logout = true;
    assert.equal(checks.pageError, undefined);
    await fs.writeFile(path.join(logs, 'browser-qa.json'), JSON.stringify(checks, null, 2));
    console.log(JSON.stringify(checks));
  } finally {
    await context.close(); await browser.close();
  }
}

run().catch(error => { console.error(error.name + ': ' + error.message); process.exitCode = 1; });
