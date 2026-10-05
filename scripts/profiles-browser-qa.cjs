const { firefox } = require('../.local-tools/browser/node_modules/playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const logs = path.join(root, 'docs/agent-work/changeo-profiles/developer/logs');
const runtime = path.join(root, '.runtime');
const base = 'http://127.0.0.1:8080';
const checks = {};
const mutationTimes = [];

async function paceMutation(page) {
  const now = Date.now();
  while (mutationTimes.length && mutationTimes[0] <= now - 61000) mutationTimes.shift();
  if (mutationTimes.length >= 18) await page.waitForTimeout(mutationTimes[0] + 61000 - now);
  mutationTimes.push(Date.now());
}

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

async function login(page, account) {
  await page.goto(base + '/login'); await page.getByLabel('Correo sintético').fill(account.contact);
  await page.getByLabel('Contraseña', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click(); await page.waitForURL('**/account');
}

async function register(page, operator, provider) {
  const account = { contact: 'p1qa' + crypto.randomUUID() + '@example.test', password: crypto.randomBytes(24).toString('base64url') };
  await page.goto(base + '/register'); await page.getByLabel('Correo sintético').fill(account.contact);
  await page.getByLabel('Contraseña (12 a 72 caracteres)').fill(account.password);
  await page.getByRole('button', { name: 'Crear cuenta', exact: true }).click(); await login(page, account);
  account.id = await page.locator('main > p code').innerText();
  const tokenFile = (await fs.readdir(path.join(runtime, 'mailbox'))).find(name => name.startsWith(account.id + '-CONTACT-'));
  await page.getByLabel('Código privado').fill((await fs.readFile(path.join(runtime, 'mailbox', tokenFile), 'utf8')).trim());
  await page.getByRole('button', { name: 'Verificar', exact: true }).click(); await page.waitForURL('**/account');
  await operator.goto(base + '/admin/accounts');
  for (const kind of provider ? ['AGE', 'PROVIDER_ELIGIBILITY', 'OPTIONAL_BADGE'] : ['AGE', 'OPTIONAL_BADGE']) {
    const card = operator.locator('section.card').filter({ has: operator.getByRole('heading', { name: account.id, exact: true }) });
    await card.getByLabel('Evidencia sintética separada').selectOption(kind);
    await card.getByLabel('Edad de prueba (solo evidencia de edad)').fill(kind === 'AGE' ? '30' : '');
    await card.getByRole('button', { name: 'Registrar evidencia sintética' }).click();
  }
  await page.getByLabel('Ver contenido sensible con advertencia').check();
  await page.getByLabel('Preferencia adulta (requiere evidencia adulta vigente)').check();
  await page.getByRole('button', { name: 'Guardar preferencias' }).click();
  return account;
}

async function compose(page, type, title, edit = false) {
  if (!edit) await page.goto(base + '/marketplace/create?type=' + type);
  const profile = type.endsWith('PROFILE');
  await page.getByLabel(profile ? 'Nombre público' : 'Título libre', { exact: true }).fill(title);
  await page.getByLabel(profile ? 'Acerca de mí y capacidades' : 'Descripción', { exact: true }).fill('Trabajo sintético: impresión y restauración de instrumentos raros. <script>dato de prueba</script>');
  await page.getByLabel('Etiquetas opcionales separadas por comas').fill('impresión,restauración');
  await page.getByLabel(profile ? 'Nombre público' : 'Título libre', { exact: true }).focus();
  await page.keyboard.press('Tab'); assert.equal(await page.locator('#body').evaluate(element => element === document.activeElement), true);
  assert.equal(await page.evaluate(() => getComputedStyle(document.activeElement).outlineWidth), '3px');
  await paceMutation(page); await page.getByRole('button', { name: 'Continuar con cobertura' }).click();
  if (edit) {
    assert.equal(await page.getByLabel('Zona amplia o localidad').inputValue(), 'La Plata');
    assert.equal(await page.getByLabel('Con envío', { exact: true }).isChecked(), true);
    assert.equal(await page.getByLabel('Disponibilidad orientativa').inputValue(), 'Lunes a viernes sintéticos');
  }
  await page.getByLabel('Provincia', { exact: true }).selectOption('Buenos Aires');
  await page.getByLabel('Zona amplia o localidad').fill('La Plata');
  for (const mode of ['Presencial', 'A distancia', 'Con envío']) await page.getByLabel(mode, { exact: true }).check();
  await page.getByLabel('Disponibilidad orientativa').fill('Lunes a viernes sintéticos');
  if (profile) await page.getByLabel('Mostrar insignia opcional vigente cuando este perfil esté aprobado').check();
  else {
    await page.getByLabel('Expectativa de precio o presupuesto en ARS (opcional)').fill('123.45');
    await page.getByLabel('Fecha orientativa (opcional)').fill('2026-12-01');
  }
  await page.getByLabel('Portafolio o adjuntos nuevos (hasta cuatro; 1 MiB cada uno)').setInputFiles({ name: 'portafolio.txt', mimeType: 'text/plain', buffer: Buffer.from('synthetic:clear') });
  await paceMutation(page); await page.getByRole('button', { name: 'Guardar borrador y ver estado' }).click(); await page.waitForURL('**/marketplace/manage/*');
  const id = page.url().split('/').pop();
  assert.ok((await page.locator('[role=status]').allTextContents()).some(value => value.includes('Borrador guardado')));
  await page.getByText('Vista previa privada de esta revisión', { exact: true }).first().click();
  assert.equal(await page.locator('script').count(), 0);
  return id;
}

async function submit(page, id) {
  await page.goto(base + '/marketplace/manage/' + id);
  await page.getByLabel('Volver a adjuntar archivos para publicar').setInputFiles({ name: 'portafolio.txt', mimeType: 'text/plain', buffer: Buffer.from('synthetic:clear') });
  await paceMutation(page); await page.getByRole('button', { name: 'Preparar revisión para publicar' }).click();
  const current = page.locator('section.card').filter({ has: page.getByRole('heading', { name: 'Revisión actual', exact: true }) });
  const revision = await current.locator('.identifier').innerText();
  assert.ok((await current.locator('[role=status]').innerText()).includes('falta enviar'));
  await paceMutation(page); await page.getByRole('button', { name: 'Enviar a revisión', exact: true }).click();
  return revision;
}

async function approve(operator, revision) {
  for (let count = 0; count < 40; count++) {
    await operator.goto(base + '/moderation/operator');
    const article = operator.locator('article').filter({ has: operator.locator('.identifier', { hasText: revision }) });
    if (await article.count()) {
      await article.getByRole('link', { name: 'Inspeccionar evidencia segura' }).click();
      assert.equal(await operator.locator('script').count(), 0);
      assert.ok((await operator.locator('main').innerText()).includes('CHANGEO_WHOLE_REVISION_V1'));
      await operator.goBack();
      const previousCases = await operator.locator('form[action="/moderation/operator/action"] input[name="caseId"]').evaluateAll(elements => elements.map(element => element.value));
      await article.getByRole('button', { name: 'Tomar revisión como caso' }).click();
      const active = operator.locator('article').filter({ has: operator.locator('details') }).filter({ has: operator.getByText('Revisar acciones del caso', { exact: true }) });
      const newCaseIds = await operator.locator('form[action="/moderation/operator/action"] input[name="caseId"]').evaluateAll(elements => elements.map(element => element.value));
      const caseId = newCaseIds.find(value => !previousCases.includes(value));
      assert.ok(caseId);
      const caseArticle = active.filter({ has: operator.locator('.identifier', { hasText: caseId }) });
      await caseArticle.getByText('Revisar acciones del caso', { exact: true }).click();
      await caseArticle.getByLabel('Acción explícita').selectOption('APPROVE');
      await caseArticle.getByRole('button', { name: 'Registrar decisión' }).click();
      return;
    }
    await operator.waitForTimeout(500);
  }
  throw new Error('Review queue did not contain exact revision');
}

async function publicView(page, route, title) {
  for (let count = 0; count < 40; count++) {
    const response = await page.goto(base + route);
    if (response.status() === 200 && (await page.getByRole('heading', { level: 1 }).innerText()) === title) return;
    await page.waitForTimeout(500);
  }
  throw new Error('Exact public activation unavailable');
}

async function bytes(page) {
  const waiting = page.waitForEvent('download');
  await page.getByRole('link', { name: 'Descargar archivo aprobado' }).click();
  const download = await waiting; assert.equal(await fs.readFile(await download.path(), 'utf8'), 'synthetic:clear');
}

async function state(page, id, action) {
  await page.goto(base + '/marketplace/manage/' + id);
  await page.getByLabel('Cambiar disponibilidad').selectOption(action);
  await paceMutation(page); await page.getByRole('button', { name: 'Actualizar disponibilidad' }).click();
  assert.equal(await page.getByText('Estado actualizado. El historial se conserva.', { exact: true }).count(), 1);
}

async function run() {
  const browser = await firefox.launch({ executablePath: '/home/paradis/.cache/ms-playwright/firefox-1553/firefox/firefox', headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, acceptDownloads: true });
  const operatorContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage(), operator = await operatorContext.newPage();
  page.on('pageerror', () => { checks.pageError = true; }); operator.on('pageerror', () => { checks.pageError = true; });
  try {
    if (process.argv.includes('--restart-check')) {
      const fixture = JSON.parse(await fs.readFile(path.join(runtime, 'p1-browser-fixture.private'), 'utf8'));
      await login(page, fixture.provider);
      await publicView(page, '/profiles/' + fixture.profile, fixture.profileTitle); await bytes(page);
      await publicView(page, '/listings/' + fixture.offer, fixture.offerTitle); await bytes(page);
      await page.goto(base + '/marketplace?q=' + encodeURIComponent(fixture.offerTitle));
      assert.equal(await page.getByRole('link', { name: fixture.offerTitle, exact: true }).count(), 1);
      checks.packagedRestartRetainedProfileListingSearchAndBytes = true;
      await fs.writeFile(path.join(logs, 'browser-restart.json'), JSON.stringify(checks, null, 2));
      return;
    }
    const enrollmentPath = process.env.P1_QA_ENROLLMENT;
    assert.ok(enrollmentPath);
    const material = Object.fromEntries((await fs.readFile(enrollmentPath, 'utf8')).split('\n').filter(line => line.includes('=')).map(line => { const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)]; }));
    await login(operator, material); await operator.goto(base + '/moderation/operator/signin');
    await operator.getByLabel('Código MFA del factor privado').fill(totp(material.totpBase32));
    await operator.getByRole('button', { name: 'Verificar MFA' }).click();
    await operator.getByLabel('Contraseña reciente (necesaria para revisión)').fill(material.password);
    await operator.getByRole('button', { name: 'Confirmar y abrir revisión' }).click(); await operator.waitForURL('**/moderation/operator');
    const fixture = {};
    checks.stage = 'providerRegistration'; fixture.provider = await register(page, operator, true);
    const token = crypto.randomUUID().slice(0, 8);
    fixture.profileTitle = 'Perfil impresión 3D ' + token;
    checks.stage = 'providerProfile'; fixture.profile = await compose(page, 'PROVIDER_PROFILE', fixture.profileTitle);
    assert.equal((await context.request.get(base + '/profiles/' + fixture.profile)).status(), 403);
    await approve(operator, await submit(page, fixture.profile));
    await publicView(page, '/profiles/' + fixture.profile, fixture.profileTitle); await bytes(page);
    assert.equal(await page.getByRole('heading', { name: 'Insignia opcional', exact: true }).count(), 1);
    checks.providerProfileCurrentBadgeSafePortfolio = true;
    fixture.offerTitle = 'Impresión 3D de piezas ' + token;
    checks.stage = 'offer'; fixture.offer = await compose(page, 'OFFER', fixture.offerTitle);
    await approve(operator, await submit(page, fixture.offer)); await publicView(page, '/listings/' + fixture.offer, fixture.offerTitle);
    for (const mode of ['LOCAL', 'REMOTE', 'SHIPPED']) {
      await page.goto(base + '/marketplace?type=OFFER&q=' + encodeURIComponent(token) + '&tag=impresión&province=Buenos%20Aires&area=La%20Plata&mode=' + mode);
      assert.equal(await page.getByRole('link', { name: fixture.offerTitle, exact: true }).count(), 1);
    }
    checks.allModesTagsCoarseAreaSpanishDiscovery = true;
    checks.stage = 'requesterRegistration'; fixture.requester = await register(page, operator, false);
    const requesterTitle = 'Restauración de instrumento raro ' + token;
    const requesterProfile = await compose(page, 'CUSTOMER_PROFILE', 'Perfil solicitante ' + token);
    await approve(operator, await submit(page, requesterProfile));
    const request = await compose(page, 'REQUEST', requesterTitle);
    await approve(operator, await submit(page, request)); await publicView(page, '/listings/' + request, requesterTitle);
    await page.goto(base + '/marketplace?type=REQUEST&q=' + encodeURIComponent(token));
    assert.equal(await page.getByRole('link', { name: requesterTitle, exact: true }).count(), 1);
    checks.bothProfilesUnusualRequestSeparateDiscovery = true;
    await publicView(page, '/listings/' + request, requesterTitle);
    await page.getByRole('link', { name: 'Vista segura para compartir' }).click();
    const share = JSON.parse(await page.locator('body').innerText()); assert.equal(share.details.title, requesterTitle);
    await publicView(page, '/listings/' + request, requesterTitle);
    await page.getByText('Reportar esta publicación', { exact: true }).click();
    await paceMutation(page); await page.getByRole('button', { name: 'Enviar reporte' }).click(); await page.waitForURL('**/moderation/cases');
    checks.exactShareReport = true;
    await state(page, request, 'CLOSE'); assert.equal((await context.request.get(base + '/listings/' + request)).status(), 403);
    await login(page, fixture.provider);
    checks.stage = 'edit'; await page.goto(base + '/marketplace/manage/' + fixture.offer + '/edit');
    await compose(page, 'OFFER', 'Pendiente invisible ' + token, true);
    await publicView(page, '/listings/' + fixture.offer, fixture.offerTitle);
    await page.goto(base + '/marketplace?q=' + encodeURIComponent('Pendiente invisible ' + token));
    assert.equal(await page.getByRole('heading', { name: 'No encontramos publicaciones' }).count(), 1);
    checks.editedCoverageRetainedAndPendingContentHidden = true;
    await state(page, fixture.offer, 'PAUSE'); assert.equal((await context.request.get(base + '/listings/' + fixture.offer + '/share')).status(), 403);
    await state(page, fixture.offer, 'RESUME'); await publicView(page, '/listings/' + fixture.offer, fixture.offerTitle);
    checks.pauseResumeClosePreserveApprovedHistory = true;
    assert.equal((await context.request.post(base + '/marketplace/state', { form: { resource: fixture.offer, action: 'CLOSE', version: '0', command: crypto.randomUUID() } })).status(), 403);
    assert.equal((await context.request.get(base + '/marketplace?page=-1')).status(), 403);
    checks.csrfAndError = true;
    for (const [name, route] of [['search', '/marketplace?q=' + token], ['profile', '/profiles/' + fixture.profile], ['listing', '/listings/' + fixture.offer], ['own', '/marketplace/manage/' + fixture.offer], ['compose', '/marketplace/create?type=REQUEST']]) {
      await page.setViewportSize({ width: 390, height: 844 }); await page.goto(base + route);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      assert.equal(await page.locator('script').count(), 0);
      await page.screenshot({ path: path.join(logs, 'browser-' + name + '-mobile.png'), fullPage: true });
    }
    checks.keyboardFocusMobileEscaping = true; assert.equal(checks.pageError, undefined);
    await fs.writeFile(path.join(runtime, 'p1-browser-fixture.private'), JSON.stringify(fixture), { mode: 0o600 });
    await fs.writeFile(path.join(logs, 'browser-qa.json'), JSON.stringify(checks, null, 2)); console.log(JSON.stringify(checks));
  } finally { await context.close(); await operatorContext.close(); await browser.close(); }
}

run().catch(async error => {
  checks.failure = error.name; checks.frames = (error.stack || '').split('\n').filter(line => line.trim().startsWith('at ')).slice(0, 3);
  await fs.writeFile(path.join(logs, 'browser-failure-' + Date.now() + '.json'), JSON.stringify(checks, null, 2));
  console.error(error.name); process.exitCode = 1;
});
