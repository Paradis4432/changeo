const { firefox } = require('../.local-tools/browser/node_modules/playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const logs = path.resolve(root, process.env.C4_QA_LOGS || 'docs/agent-work/changeo-conversations/developer/browser');
const fixturePath = path.resolve(root, process.env.C4_QA_FIXTURE || '.runtime/c4-browser-fixture.private');
const base = process.env.C4_QA_BASE || 'http://127.0.0.1:8080';
assert.equal(new URL(base).hostname, '127.0.0.1');
const checks = {};
const mutations = [];
let operatorMaterial;
let operatorProofAt = 0;
let operatorCounter = -1;

async function pace(page) {
  let now = Date.now();
  while (mutations.length && mutations[0] <= now - 61000) mutations.shift();
  if (mutations.length >= 16) await page.waitForTimeout(mutations[0] + 61000 - now);
  mutations.push(Date.now());
}
async function click(page, locator) {
  await pace(page);
  await locator.click();
}
async function operatorProof(page) {
  if (Date.now() - operatorProofAt < 480000) return;
  if (Math.floor(Date.now() / 30000) <= operatorCounter) await page.waitForTimeout((operatorCounter + 1) * 30000 - Date.now() + 100);
  await page.goto(base + '/moderation/operator/signin');
  operatorCounter = Math.floor(Date.now() / 30000);
  await page.getByLabel('Código MFA del factor privado').fill(totp(operatorMaterial.totpBase32));
  await click(page, page.getByRole('button', { name: 'Verificar MFA' }));
  await page.getByLabel('Contraseña reciente (necesaria para revisión)').fill(operatorMaterial.password);
  await click(page, page.getByRole('button', { name: 'Confirmar y abrir revisión' }));
  operatorProofAt = Date.now();
}
function totp(secret) {
  let buffer = 0, bits = 0; const bytes = [];
  for (const character of secret) {
    buffer = (buffer << 5) | 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'.indexOf(character); bits += 5;
    if (bits >= 8) { bits -= 8; bytes.push((buffer >> bits) & 255); }
  }
  const counter = Buffer.alloc(8); counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const digest = crypto.createHmac('sha1', Buffer.from(bytes)).update(counter).digest();
  return String((digest.readUInt32BE(digest[digest.length - 1] & 15) & 0x7fffffff) % 1000000).padStart(6, '0');
}
async function login(page, account) {
  await page.goto(base + '/login'); await page.getByLabel('Correo sintético').fill(account.contact);
  await page.getByLabel('Contraseña', { exact: true }).fill(account.password);
  await click(page, page.getByRole('button', { name: 'Ingresar', exact: true })); await page.waitForURL('**/account');
}
async function register(page, operator, provider, age = 30) {
  await operatorProof(operator);
  const account = { contact: 'c4qa' + crypto.randomUUID() + '@example.test', password: crypto.randomBytes(24).toString('base64url') };
  await page.goto(base + '/register'); await page.getByLabel('Correo sintético').fill(account.contact);
  await page.getByLabel('Contraseña (12 a 72 caracteres)').fill(account.password);
  await click(page, page.getByRole('button', { name: 'Crear cuenta', exact: true })); await login(page, account);
  account.id = await page.locator('main > p code').innerText();
  const mailbox = path.resolve(root, process.env.C4_QA_MAILBOX || '.runtime/mailbox');
  const token = (await fs.readdir(mailbox)).find(name => name.startsWith(account.id + '-CONTACT-'));
  await page.getByLabel('Código privado').fill((await fs.readFile(path.join(mailbox, token), 'utf8')).trim());
  await click(page, page.getByRole('button', { name: 'Verificar', exact: true }));
  await operator.goto(base + '/admin/accounts');
  for (const kind of ['AGE', ...(provider ? ['PROVIDER_ELIGIBILITY'] : []), ...(age < 18 ? ['MINOR_DRAFT'] : [])]) {
    const card = operator.locator('section.card').filter({ has: operator.getByRole('heading', { name: account.id, exact: true }) });
    await card.getByLabel('Evidencia sintética separada').selectOption(kind);
    await card.getByLabel('Edad de prueba (solo evidencia de edad)').fill(kind === 'AGE' ? String(age) : '');
    await click(operator, card.getByRole('button', { name: 'Registrar evidencia sintética' }));
  }
  if (age >= 18) {
    await page.getByLabel('Ver contenido sensible con advertencia').check();
    await page.getByLabel('Preferencia adulta (requiere evidencia adulta vigente)').check();
    await click(page, page.getByRole('button', { name: 'Guardar preferencias' }));
  }
  return account;
}
async function listing(page, type, title) {
  await page.goto(base + '/marketplace/create?type=' + type);
  await page.getByLabel('Título libre', { exact: true }).fill(title);
  await page.getByLabel('Descripción', { exact: true }).fill('Coordinación sintética privada de un servicio en Argentina');
  await click(page, page.getByRole('button', { name: 'Continuar con cobertura' }));
  await page.getByLabel('Provincia', { exact: true }).selectOption('Buenos Aires');
  await page.getByLabel('Zona amplia o localidad').fill('La Plata');
  for (const mode of ['Presencial', 'A distancia', 'Con envío']) await page.getByLabel(mode, { exact: true }).check();
  await page.getByLabel('Disponibilidad orientativa').fill('A coordinar en privado');
  await click(page, page.getByRole('button', { name: 'Guardar borrador y ver estado' }));
  await page.waitForURL('**/marketplace/manage/*'); const id = page.url().split('/').pop();
  await click(page, page.getByRole('button', { name: 'Preparar revisión para publicar' }));
  const current = page.locator('section.card').filter({ has: page.getByRole('heading', { name: 'Revisión actual', exact: true }) });
  const revision = await current.locator('.identifier').innerText();
  await click(page, page.getByRole('button', { name: 'Enviar a revisión', exact: true }));
  return { id, revision, title };
}
async function moderate(operator, revision, expectedBody, action = 'APPROVE') {
  await operatorProof(operator);
  for (let attempt = 0; attempt < 50; attempt++) {
    await operator.goto(base + '/moderation/operator');
    let article = operator.locator('article').filter({ has: operator.locator('.identifier', { hasText: revision }) });
    if (await article.count()) {
      await article.getByRole('link', { name: 'Inspeccionar evidencia segura' }).click();
      if (expectedBody) assert.ok((await operator.locator('main').innerText()).includes(expectedBody));
      assert.equal(await operator.locator('script').count(), 0);
      await operator.goBack();
      const before = await operator.locator('input[name=caseId]').evaluateAll(elements => elements.map(element => element.value));
      await click(operator, article.getByRole('button', { name: 'Tomar revisión como caso' }));
      const after = await operator.locator('input[name=caseId]').evaluateAll(elements => elements.map(element => element.value));
      const caseId = after.find(value => !before.includes(value)); assert.ok(caseId);
      article = operator.locator('article').filter({ has: operator.locator('.identifier', { hasText: caseId }) });
      await article.getByText('Revisar acciones del caso', { exact: true }).click();
      await article.getByLabel('Acción explícita').selectOption(action);
      await click(operator, article.getByRole('button', { name: 'Registrar decisión' })); return;
    }
    await operator.waitForTimeout(500);
  }
  throw new Error('Exact revision missing from focused queue');
}
async function publicQuestion(page, operator, item, body) {
  await waitText(page, '/listings/' + item.id, item.title);
  await openDiscussionIndex(page, 'Mis preguntas y comentarios: estado, edición y recuperación', 'No hay textos propios en esta página.');
  await waitText(page, '/listings/' + item.id, item.title);
  await page.getByLabel('Texto público sintético').fill(body);
  await click(page, page.getByRole('button', { name: 'Enviar texto público a revisión' }));
  assert.ok((await page.locator('main').innerText()).includes(body));
  const form = page.locator('form[action="/conversations/authored/submit"]');
  const reference = { resourceId: await form.locator('[name=resource]').inputValue(), revisionId: await form.locator('[name=revision]').inputValue(), ownerKind: 'QUESTION', ownRoute: new URL(page.url()).pathname + new URL(page.url()).search };
  const revision = reference.revisionId;
  await openDiscussionIndex(page, 'Mis preguntas y comentarios desde el comienzo', body);
  await page.goto(base + '/listings/' + item.id);
  assert.ok(!(await page.locator('article .authored').allTextContents()).includes(body));
  await openDiscussionIndex(page, 'Mis preguntas y comentarios: estado, edición y recuperación', body);
  await page.goto(base + '/listings/' + item.id);
  assert.ok(revision); await moderate(operator, revision, body); await waitText(page, '/listings/' + item.id, body);
  assert.ok(!(await page.locator('main').innerText()).includes('Destino de prueba'));
  return reference;
}
async function openDiscussionIndex(page, name, expected) {
  const link = page.getByRole('link', { name, exact: true });
  const route = new URL(await link.getAttribute('href'), base);
  assert.equal(route.pathname, '/conversations/discussion/mine');
  assert.equal(route.searchParams.has('after'), false);
  const [response] = await Promise.all([page.waitForResponse(value => value.url() === route.href), link.click()]);
  assert.equal(response.status(), 200);
  assert.ok((await page.locator('main').innerText()).includes(expected));
  checks.publicDefaultEmptyNonemptyListingEntryAndOwnReentry = true;
}
async function waitText(page, route, text, selector = 'main') {
  for (let attempt = 0; attempt < 50; attempt++) {
    const response = await page.goto(base + route);
    if (response.status() === 200 && (await page.locator(selector).innerText()).includes(text)) return;
    await page.waitForTimeout(500);
  }
  throw new Error('Expected current approved projection unavailable');
}
async function respond(page, item, mode, subject) {
  await waitText(page, '/listings/' + item.id, item.title);
  await page.getByLabel('Modalidad', { exact: true }).selectOption(mode);
  if (subject) await page.getByLabel('Actuar por').first().selectOption(subject);
  await click(page, page.locator('form[action="/conversations/respond"] button'));
  await page.waitForURL('**/conversations/*?subject=*');
  return { thread: new URL(page.url()).pathname.split('/').pop(), subject: new URL(page.url()).searchParams.get('subject') };
}
async function message(page, journey, body, proposal = false) {
  await page.goto(base + '/conversations/' + journey.thread + '?subject=' + journey.subject);
  await page.getByLabel('Contenido', { exact: true }).selectOption(proposal ? 'PROPOSAL' : 'PRIVATE_MESSAGE');
  await page.getByLabel('Mensaje o propuesta').fill(body);
  await page.getByLabel('Archivos sintéticos privados (hasta cuatro, 1 MiB cada uno)').setInputFiles({ name: 'privado.txt', mimeType: 'text/plain', buffer: Buffer.from('synthetic:clear') });
  await click(page, page.getByRole('button', { name: 'Guardar sin entregar', exact: true }));
  const form = page.locator('form[action="/conversations/submit"]').first();
  const reference = { resourceId: await form.locator('input[name=resource]').inputValue(), revisionId: await form.locator('input[name=revision]').inputValue(), ownerKind: await form.locator('input[name=kind]').inputValue() };
  await click(page, form.locator('button')); return reference;
}
async function quote(page, journey, mode, scope, arrangement = mode === 'SHIPPED' ? 'SHIPPING' : mode === 'LOCAL' ? 'APPOINTMENT' : 'REMOTE') {
  await page.goto(base + '/conversations/' + journey.thread + '?subject=' + journey.subject);
  await page.getByRole('link', { name: 'Preparar presupuesto en dos pasos' }).click();
  await page.getByLabel('Alcance', { exact: true }).fill(scope);
  await page.getByLabel('Precio del servicio (ARS)').fill('123.45'); await page.getByLabel('Costo de envío o retiro (ARS)').fill(mode === 'SHIPPED' ? '5.00' : '0.00');
  await page.getByLabel('Entregables', { exact: true }).fill('Una pieza y archivos finales');
  await page.getByLabel('Alcance', { exact: true }).focus(); await page.keyboard.press('Tab');
  assert.equal(await page.getByLabel('Precio del servicio (ARS)').evaluate(element => element === document.activeElement), true);
  assert.equal(await page.evaluate(() => getComputedStyle(document.activeElement).outlineWidth), '3px');
  await click(page, page.getByRole('button', { name: 'Continuar a coordinación y revisión' }));
  await page.getByLabel('Coordinación', { exact: true }).selectOption(arrangement);
  await page.getByLabel('Detalle de coordinación').fill('Entrega privada sintética acordada');
  await page.getByLabel('Instrucciones privadas sintéticas').fill('Destino de prueba; ningún domicilio real');
  await page.getByLabel('Fecha y hora de finalización (Argentina)').fill('2026-12-01T15:30');
  await page.getByLabel('Rondas incluidas (0 a 10)').fill('2'); await page.getByLabel('Evidencia final', { exact: true }).selectOption('TEXT');
  await page.getByLabel('Qué demuestra que está terminado').fill('Descripción y evidencia final moderada');
  await page.getByLabel('Ventana técnica privada de revisión (minutos)').fill('60');
  await page.getByLabel('Adjuntos sintéticos privados').setInputFiles({ name: 'terminos.txt', mimeType: 'text/plain', buffer: Buffer.from('synthetic:clear') });
  await click(page, page.getByRole('button', { name: 'Guardar presupuesto privado' }));
  const form = page.locator('form[action="/agreements/submit"]');
  const reference = { resourceId: await form.locator('input[name=resource]').inputValue(), revisionId: await form.locator('input[name=revision]').inputValue(), ownerKind: 'QUOTE_DESCRIPTION' };
  await page.locator('article.card section details summary').click();
  const terms = await page.locator('article.card section').innerText();
  assert.ok(terms.includes({ SHIPPING: 'Envío', PICKUP: 'Retiro', APPOINTMENT: 'Encuentro presencial', REMOTE: 'Entrega remota' }[arrangement]));
  for (const disclosed of ['ARS 123.45', mode === 'SHIPPED' ? 'ARS 5.00' : 'ARS 0.00', 'Una pieza y archivos finales', 'Entrega privada sintética acordada', 'Destino de prueba; ningún domicilio real', 'Descripción y evidencia final moderada', '60', 'solo aceptación expresa', 'El silencio no completa, acepta ni libera fondos']) assert.ok(terms.includes(disclosed));
  await click(page, form.locator('button')); return reference;
}
async function assent(page, journey, reference) {
  await waitText(page, '/conversations/' + journey.thread + '?subject=' + journey.subject, 'ARS 123.45');
  const form = page.locator('form[action="/agreements/accept"]').filter({ has: page.locator('input[name=resource][value="' + reference.resourceId + '"]') });
  await click(page, form.locator('button')); await page.waitForURL('**/conversations/*?subject=*');
}
async function capture(page, journey) {
  const route = '/conversations/' + journey.thread + '?subject=' + journey.subject;
  await page.goto(base + route);
  const delivered = await page.locator('#delivered-messages article').evaluateAll(elements => elements.map(element => ({ revision: element.dataset.messageRevision, body: element.querySelector('.authored').textContent, links: [...element.querySelectorAll('a')].map(link => link.getAttribute('href')) })));
  const capture = page.locator('details pre.authored');
  const context = await capture.count() ? await capture.innerText() : null;
  const captureTitle = await page.locator('main h1').innerText();
  const terms = await page.locator('form[action="/agreements/accept"]').evaluateAll(elements => elements.map(form => {
    const section = form.parentElement.querySelector('section');
    const disclosures = Object.fromEntries([...section.querySelectorAll('dl.status dt')].map(label => [label.textContent.trim(), label.nextElementSibling.textContent.replace(/\s+/g, ' ').trim()]));
    return { resource: form.querySelector('[name=resource]').value, revision: form.querySelector('[name=revision]').value, version: form.querySelector('[name=version]').value,
      text: section.textContent.replace(/\s+/g, ' ').trim(), disclosures, files: [...section.querySelectorAll('a')].map(link => link.getAttribute('href')) };
  }));
  const jobRoute = await page.getByRole('link', { name: 'Consultar trabajo y próximos pasos' }).getAttribute('href');
  await page.goto(base + jobRoute);
  const job = (await page.locator('main').innerText()).replace(/\s+/g, ' ').trim();
  const files = [...delivered.flatMap(message => message.links), ...terms.flatMap(term => term.files || []), ...await page.locator('a[href^="/conversations/files/"]').evaluateAll(elements => elements.map(element => element.getAttribute('href')))];
  const hashes = {};
  for (const link of new Set(files)) { const response = await page.request.get(base + link); assert.equal(response.status(), 200); const bytes = await response.body(); assert.equal(bytes.toString(), 'synthetic:clear'); hashes[link] = crypto.createHash('sha256').update(bytes).digest('hex'); }
  return { context, captureTitle, delivered, terms, jobRoute, job, hashes };
}
async function mobile(page, name, route) {
  await page.setViewportSize({ width: 390, height: 844 }); await page.goto(base + route);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({ path: path.join(logs, name + '-mobile.png'), fullPage: true });
}
async function csrf(page) { return page.locator('input[name="_csrf"]').first().inputValue(); }
async function post(page, route, fields) {
  await pace(page);
  const response = await page.request.post(base + route, { form: { ...fields, _csrf: await csrf(page) }, maxRedirects: 0 });
  assert.equal(response.status(), 302);
  const destination = new URL(response.headers().location, base); assert.equal(destination.origin, new URL(base).origin);
  await page.goto(destination.href);
}
async function decide(operator, reference, action, label = 'GENERAL') {
  await operatorProof(operator);
  await operator.goto(base + '/moderation/operator');
  const before = await operator.locator('[name=caseId]').evaluateAll(elements => elements.map(element => element.value));
  await post(operator, '/moderation/operator/case', { command: crypto.randomUUID(), kind: reference.ownerKind, resource: reference.resourceId, revision: reference.revisionId });
  const after = await operator.locator('[name=caseId]').evaluateAll(elements => elements.map(element => element.value));
  const id = after.find(value => !before.includes(value)); assert.ok(id);
  const form = operator.locator('form[action="/moderation/operator/action"]').filter({ has: operator.locator('[name=caseId][value="' + id + '"]') });
  await post(operator, '/moderation/operator/action', { command: crypto.randomUUID(), caseId: id, version: await form.locator('[name=version]').inputValue(), action, label, reason: 'SAFETY' });
}
async function appeal(page, operator, form) {
  await operatorProof(operator);
  await click(page, form.locator('button'));
  const id = await page.locator('main section .identifier').first().innerText();
  for (const action of ['TAKE', 'APPROVE']) {
    await operator.goto(base + '/moderation/operator');
    const actionForm = operator.locator('form[action="/moderation/operator/action"]').filter({ has: operator.locator('[name=caseId][value="' + id + '"]') });
    await post(operator, '/moderation/operator/action', { command: crypto.randomUUID(), caseId: id, version: await actionForm.locator('[name=version]').inputValue(), action, label: 'GENERAL', reason: 'FALSE_POSITIVE' });
  }
}
async function publicCorrection(page, operator, item, original, body) {
  await page.goto(base + original.ownRoute);
  await page.getByText('Editar texto público: crea una revisión nueva', { exact: true }).click();
  await page.getByLabel('Nuevo texto público').fill(body);
  await click(page, page.getByRole('button', { name: 'Guardar y enviar nueva revisión pública' }));
  const form = page.locator('form[action="/conversations/authored/submit"]');
  const edited = { ...original, revisionId: await form.locator('[name=revision]').inputValue() };
  assert.notEqual(edited.revisionId, original.revisionId);
  await page.goto(base + '/listings/' + item.id);
  assert.ok(!(await page.locator('main').innerText()).includes(body));
  await moderate(operator, edited.revisionId, body, 'REJECT');
  await page.goto(base + original.ownRoute);
  await appeal(page, operator, page.locator('form[action="/conversations/cases"]'));
  await waitText(page, '/listings/' + item.id, body);
  const report = page.locator('form[action="/conversations/cases"]').filter({ has: page.locator('[name=revision][value="' + edited.revisionId + '"]') });
  assert.equal(await report.locator('[name=caseKind]').inputValue(), 'REPORT');
  await click(page, report.locator('button'));
  await decide(operator, edited, 'RECALL');
  await page.goto(base + '/listings/' + item.id); assert.ok(!(await page.locator('main').innerText()).includes(body));
  await page.goto(base + original.ownRoute); assert.ok((await page.locator('main').innerText()).includes(body));
  await openDiscussionIndex(page, 'Mis preguntas y comentarios desde el comienzo', body);
  checks.publicImmutableEditRejectAppealReportRecallPrivateRecovery = true;
}
async function boundedUploads(page, journey) {
  const route = '/conversations/' + journey.thread + '?subject=' + journey.subject;
  await page.goto(base + route);
  const before = await ownedCounts(journey);
  await page.getByLabel('Mensaje o propuesta').fill('Cuatro archivos sintéticos en el límite');
  const files = Array.from({ length: 4 }, (_, index) => ({ name: 'limite-' + index + '.txt', mimeType: 'text/plain', buffer: Buffer.alloc(1048576, 97) }));
  await page.getByLabel('Archivos sintéticos privados (hasta cuatro, 1 MiB cada uno)').setInputFiles(files);
  await click(page, page.getByRole('button', { name: 'Guardar sin entregar', exact: true }));
  assert.ok((await page.locator('main').innerText()).includes('Cuatro archivos sintéticos en el límite'));
  const saved = await ownedCounts(journey); assert.equal(saved.resources, before.resources + 1); assert.equal(saved.revisions, before.revisions + 1); assert.equal(saved.attachments, before.attachments + 4);
  for (const [name, uploads, body] of [['perFile', [{ ...files[0], buffer: Buffer.alloc(1048577, 97) }], 'Archivo demasiado grande'], ['count', Array.from({ length: 5 }, (_, index) => ({ ...files[0], name: 'exceso-' + index + '.txt', buffer: Buffer.from('synthetic:clear') })), 'Cinco archivos'], ['aggregate', files, 'a'.repeat(1200000)]]) {
    await page.goto(base + route);
    const fields = { command: crypto.randomUUID(), thread: journey.thread, subject: journey.subject, kind: 'PRIVATE_MESSAGE', label: 'GENERAL', _csrf: await csrf(page) };
    let response;
    try {
      response = await page.evaluate(async ({ fields, uploads, body }) => {
        const form = new FormData(); for (const [key, value] of Object.entries(fields)) form.append(key, value);
        form.append('body', body.text ?? 'a'.repeat(body.length));
        for (const file of uploads) form.append('uploads', new Blob([new Uint8Array(file.size).fill(97)], { type: file.mimeType }), file.name);
        const result = await fetch('/conversations/messages', { method: 'POST', headers: { Accept: 'text/html' }, body: form });
        const document = new DOMParser().parseFromString(await result.text(), 'text/html');
        return { status: result.status, text: (document.querySelector('main')?.textContent || document.querySelector('body')?.textContent || '').trim().slice(0, 300) };
      }, { fields, body: { text: body.length <= 12000 ? body : null, length: body.length }, uploads: uploads.map(file => ({ name: file.name, mimeType: file.mimeType, size: file.buffer.length })) });
    } catch (failure) {
      const unchanged = await ownedCounts(journey);
      await fs.writeFile(path.join(logs, 'upload-' + name + '-transport.json'), JSON.stringify({ failure: failure.name, message: failure.message.split('\n')[0].slice(0, 200), negativeCountsUnchanged: JSON.stringify(unchanged) === JSON.stringify(saved) }, null, 2), { flag: 'wx' });
      throw failure;
    }
    const unchanged = await ownedCounts(journey);
    await fs.writeFile(path.join(logs, 'upload-' + name + '.json'), JSON.stringify({ status: response.status, message: response.text, savedChanges: { resources: saved.resources - before.resources, revisions: saved.revisions - before.revisions, attachments: saved.attachments - before.attachments }, negativeCountsUnchanged: JSON.stringify(unchanged) === JSON.stringify(saved) }, null, 2), { flag: 'wx' });
    assert.ok([400, 413].includes(response.status)); assert.ok(response.text.includes('adjunt') || response.text.includes('Adjunt'));
    assert.deepEqual(unchanged, saved); checks['realServletRejectedUpload' + name] = true;
  }
  checks.realServletFourOneMiBFilesPrivateSaveNoNegativeEffects = true;
}
async function ownedCounts(journey) {
  assert.match(journey.thread, /^[0-9a-f-]{36}$/);
  const secret = (await fs.readFile(path.join(root, '.runtime/db.password'), 'utf8')).trim();
  const schema = process.env.C4_QA_SCHEMA || 'sandbox'; assert.match(schema, /^(sandbox|conversations_browser_[a-f0-9]{32})$/);
  const database = process.env.C4_QA_DATABASE || 'changeo'; assert.match(database, /^[a-z][a-z0-9_]{0,62}$/);
  const sql = "select json_build_object('resources',(select count(*) from " + schema + ".conversations_authored where thread_id='" + journey.thread + "'),'revisions',(select count(*) from " + schema + ".conversations_revision r join " + schema + ".conversations_authored a on a.id=r.resource_id where a.thread_id='" + journey.thread + "'),'attachments',(select count(*) from " + schema + ".conversations_attachment f join " + schema + ".conversations_revision r on r.id=f.revision_id join " + schema + ".conversations_authored a on a.id=r.resource_id where a.thread_id='" + journey.thread + "'),'files',(select count(*) from " + schema + ".files_artifact))";
  return JSON.parse(execFileSync(path.join(root, '.local-tools/postgres/bin/psql'), ['-X', '-h', '127.0.0.1', '-p', '55432', '-U', 'changeo', '-d', database, '-Atc', sql], { env: { ...process.env, PGPASSWORD: secret }, encoding: 'utf8' }));
}
async function fundedWorkflow(page, operator, fixture) {
  const route = '/jobs/' + fixture.jobId;
  await login(page, fixture.provider); await page.goto(base + route + '?subject=' + fixture.provider.id);
  assert.equal(await page.getByRole('button', { name: 'Comenzar con financiación completa' }).isDisabled(), false);
  await click(page, page.getByRole('button', { name: 'Comenzar con financiación completa' }));
  await page.getByLabel('Etapa', { exact: true }).selectOption('FINAL_DELIVERY');
  await page.getByLabel('Descripción', { exact: true }).fill('Entrega final sintética original');
  await click(page, page.getByRole('button', { name: 'Guardar sin entregar', exact: true }));
  let form = page.locator('form[action="/jobs/submit"]');
  const deliveryId = await form.locator('[name=resource]').inputValue();
  const original = await form.locator('[name=revision]').inputValue();
  await click(page, form.locator('button')); await moderate(operator, original, 'Entrega final sintética original');
  await waitText(page, route + '?subject=' + fixture.provider.id + '&content=' + deliveryId, 'Entrega final pendiente de aceptación expresa');
  await page.getByText('Editar evidencia: crea una revisión nueva', { exact: true }).click();
  await page.getByLabel('Nuevo texto de evidencia').fill('Entrega final sintética corregida');
  await click(page, page.getByRole('button', { name: 'Guardar evidencia corregida' }));
  form = page.locator('form[action="/jobs/submit"]'); const corrected = await form.locator('[name=revision]').inputValue(); assert.notEqual(corrected, original);
  await login(page, fixture.customer); await page.goto(base + route + '?subject=' + fixture.customer.id);
  assert.ok((await page.locator('main').innerText()).includes('Entrega final sintética original')); assert.ok(!(await page.locator('main').innerText()).includes('Entrega final sintética corregida'));
  await login(page, fixture.provider); await page.goto(base + route + '?subject=' + fixture.provider.id + '&content=' + deliveryId);
  await click(page, page.locator('form[action="/jobs/submit"] button')); await moderate(operator, corrected, 'Entrega final sintética corregida');
  await login(page, fixture.customer); await waitText(page, route + '?subject=' + fixture.customer.id, 'Entrega final sintética corregida');
  await click(page, page.getByRole('button', { name: 'Acepto expresamente la entrega final exacta' }));
  assert.ok((await page.locator('main').innerText()).includes('Trabajo completado por aceptación expresa'));
  await page.getByLabel('Etapa', { exact: true }).selectOption('REVIEW'); await page.getByLabel('Descripción', { exact: true }).fill('Reseña sintética original rechazada');
  await page.getByLabel('Valoración, solo para una reseña').fill('3'); await click(page, page.getByRole('button', { name: 'Guardar sin entregar', exact: true }));
  form = page.locator('form[action="/jobs/submit"]'); const reviewId = await form.locator('[name=resource]').inputValue(); const rejected = await form.locator('[name=revision]').inputValue();
  await click(page, form.locator('button')); await moderate(operator, rejected, 'Reseña sintética original rechazada', 'REJECT');
  await page.goto(base + route + '?subject=' + fixture.customer.id + '&content=' + reviewId);
  await appeal(page, operator, page.locator('form[action="/conversations/cases"]').filter({ has: page.locator('[name=caseKind][value=APPEAL]') }));
  await waitText(page, route + '?subject=' + fixture.customer.id + '&content=' + reviewId, 'Reseña sintética original rechazada', 'main > section:first-of-type');
  await page.getByText('Editar evidencia: crea una revisión nueva', { exact: true }).click(); await page.getByLabel('Nuevo texto de evidencia').fill('Reseña corregida en el mismo recurso'); await page.getByLabel('Valoración corregida').fill('5');
  const [editResponse] = await Promise.all([page.waitForResponse(response => response.request().isNavigationRequest() && response.request().method() === 'POST' && new URL(response.url()).pathname === '/jobs/content'), click(page, page.getByRole('button', { name: 'Guardar evidencia corregida' }))]);
  await fs.writeFile(path.join(logs, 'review-correction-response.json'), JSON.stringify({ status: editResponse.status(), exactResource: true }, null, 2), { flag: 'wx' });
  assert.equal(editResponse.status(), 302); form = page.locator('form[action="/jobs/submit"]'); assert.equal(await form.locator('[name=resource]').inputValue(), reviewId);
  const reviewRevision = await form.locator('[name=revision]').inputValue(); await click(page, form.locator('button')); await moderate(operator, reviewRevision, 'Reseña corregida en el mismo recurso');
  await login(page, fixture.provider); await waitText(page, route + '?subject=' + fixture.provider.id, 'Reseña corregida en el mismo recurso');
  assert.ok((await page.locator('main').innerText()).includes('Valoración: 5 de 5'));
  const report = page.locator('form[action="/conversations/cases"]').filter({ has: page.locator('[name=revision][value="' + reviewRevision + '"]') }); await click(page, report.locator('button'));
  await mobile(page, 'completed-review', route + '?subject=' + fixture.provider.id);
  checks.testOnlyExactNegotiatedFundingFinalEditExplicitCompletionUniqueReviewAppealEditReport = true;
}
async function run() {
  await fs.mkdir(logs, { recursive: true });
  const browser = await firefox.launch({ executablePath: '/home/paradis/.cache/ms-playwright/firefox-1553/firefox/firefox', headless: true });
  const contexts = [];
  async function newPage() { const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, acceptDownloads: true }); contexts.push(context); const page = await context.newPage(); page.on('pageerror', () => { checks.pageError = true; }); return page; }
  try {
    const page = await newPage();
    if (process.argv.includes('--restart-check') || process.argv.includes('--restore-check')) {
      const fixture = JSON.parse(await fs.readFile(fixturePath, 'utf8'));
      for (const journey of fixture.journeys) { await login(page, journey.actor); assert.deepEqual(await capture(page, journey), journey.projection); }
      await login(page, fixture.provider); assert.equal((await page.request.get(base + '/listings/' + fixture.offer.id)).status(), fixture.offerRecalled ? 403 : 200);
      assert.equal((await page.request.get(base + '/listings/' + fixture.request.id)).status(), 403);
      await login(page, fixture.stranger); assert.equal((await page.request.get(base + '/conversations/' + fixture.journeys[0].thread + '?subject=' + fixture.stranger.id)).status(), 403);
      if (fixture.guardianHistory) {
        await login(page, fixture.customer);
        await page.goto(base + fixture.guardianHistory.threadRoute); assert.equal(await page.locator('form[action="/conversations/messages"]').count(), 0); assert.equal(await page.locator('details pre.authored').count(), 0);
        await page.goto(base + fixture.guardianHistory.helpRoute); assert.equal((await page.locator('main').innerText()).replace(/\s+/g, ' ').trim(), fixture.guardianHistory.text);
        const file = await page.request.get(base + fixture.guardianHistory.file); assert.equal(file.status(), 200); assert.equal(crypto.createHash('sha256').update(await file.body()).digest('hex'), fixture.guardianHistory.sha256);
        await login(page, fixture.minor); assert.equal((await page.request.get(base + fixture.guardianHistory.file)).status(), 403); assert.equal((await page.request.get(base + fixture.guardianHistory.threadRoute)).status(), 403);
      }
      const name = process.argv.includes('--restore-check') ? 'restore' : 'restart'; checks[name + 'ExactSemanticFieldsReferencesBytesPermissions'] = true;
      await fs.writeFile(path.join(logs, name + '.json'), JSON.stringify(checks, null, 2)); console.log(JSON.stringify(checks)); return;
    }
    const operator = await newPage();
    const material = Object.fromEntries((await fs.readFile(process.env.C4_QA_ENROLLMENT, 'utf8')).split('\n').filter(line => line.includes('=')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)]; }));
    operatorMaterial = material; await login(operator, material); await operatorProof(operator);
    if (process.argv.includes('--funded-workflow')) {
      await fundedWorkflow(page, operator, JSON.parse(await fs.readFile(fixturePath, 'utf8')));
      await fs.writeFile(path.join(logs, 'funded-workflow.json'), JSON.stringify(checks, null, 2)); console.log(JSON.stringify(checks)); return;
    }
    const fixture = { journeys: [] }; const token = crypto.randomUUID().slice(0, 8);
    checks.stage = 'register'; fixture.provider = await register(page, operator, true); fixture.customer = await register(page, operator, false);
    await login(page, fixture.provider); checks.stage = 'offer'; fixture.offer = await listing(page, 'OFFER', 'Impresión 3D ' + token); await moderate(operator, fixture.offer.revision);
    if (process.argv.includes('--upload-check')) {
      await login(page, fixture.customer); const uploadJourney = await respond(page, fixture.offer, 'REMOTE');
      await fs.writeFile(fixturePath + '.upload', JSON.stringify(fixture), { mode: 0o600, flag: 'wx' });
      checks.stage = 'upload-check'; await boundedUploads(page, uploadJourney); console.log(JSON.stringify(checks)); return;
    }
    if (process.argv.includes('--funded-setup')) {
      await login(page, fixture.customer); const setup = await respond(page, fixture.offer, 'REMOTE');
      const providerPage = await newPage(); await login(providerPage, fixture.provider); const providerSide = { ...setup, subject: fixture.provider.id };
      const terms = await quote(providerPage, providerSide, 'REMOTE', 'Trabajo dinámico para comprobar correcciones'); await moderate(operator, terms.revisionId);
      await assent(page, setup, terms); await assent(providerPage, providerSide, terms);
      await page.goto(base + '/conversations/' + setup.thread + '?subject=' + setup.subject); const route = await page.getByRole('link', { name: 'Consultar trabajo y próximos pasos' }).getAttribute('href');
      fixture.jobId = new URL(base + route).pathname.split('/').pop(); fixture.terms = terms;
      await fs.writeFile(fixturePath, JSON.stringify(fixture), { mode: 0o600, flag: 'wx' });
      await fs.writeFile(fixturePath + '.ready', [fixture.jobId, terms.resourceId, terms.revisionId].join('\n'), { mode: 0o600, flag: 'wx' });
      checks.realDynamicBilateralJobReadyForTestOnlyReadOnlyFact = true; console.log(JSON.stringify(checks)); return;
    }
    await login(page, fixture.customer); checks.stage = 'publicQuestion'; const publicReference = await publicQuestion(page, operator, fixture.offer, 'Pregunta pública sobre materiales ' + token); checks.approvedPublicQuestionNoPrivateData = true;
    checks.stage = 'publicCorrection'; await publicCorrection(page, operator, fixture.offer, publicReference, 'Pregunta pública corregida por su autor ' + token);
    let journey = await respond(page, fixture.offer, 'REMOTE'); journey.actor = fixture.customer;
    checks.stage = 'message'; const body = 'Modelo privado <script>sin ejecución</script> ' + token; const messageReference = await message(page, journey, body);
    const recipient = await newPage(); await login(recipient, fixture.provider);
    await recipient.goto(base + '/conversations/' + journey.thread + '?subject=' + fixture.provider.id);
    assert.ok(!(await recipient.locator('main').innerText()).includes(body)); assert.equal(await recipient.locator('[data-message-revision="' + messageReference.revisionId + '"]').count(), 0);
    await moderate(operator, messageReference.revisionId, body);
    await waitText(recipient, '/conversations/' + journey.thread + '?subject=' + fixture.provider.id, body, '#delivered-messages');
    assert.equal(await recipient.locator('script:not([src="/conversations-poll.js"])').count(), 0);
    checks.pendingPrivateAndApprovedEscapedExactMessage = true;
    checks.stage = 'boundedUploads'; await boundedUploads(page, journey);
    const independent = await message(page, journey, 'Segundo borrador independiente ' + token);
    await page.goto(base + '/conversations/' + journey.thread + '?subject=' + journey.subject + '&message=' + messageReference.resourceId);
    assert.ok((await page.locator('main').innerText()).includes(body));
    await page.goto(base + '/conversations/' + journey.thread + '?subject=' + journey.subject + '&message=' + independent.resourceId);
    assert.ok((await page.locator('main').innerText()).includes('Segundo borrador independiente ' + token));
    checks.exactIndependentApprovedAndPendingDraftReachability = true;
    checks.stage = 'offerQuote'; const providerJourney = { ...journey, subject: fixture.provider.id }; const quoteReference = await quote(recipient, providerJourney, 'REMOTE', 'Imprimir pieza 3D completa ' + token);
    await moderate(operator, quoteReference.revisionId, 'Imprimir pieza 3D completa ' + token); await assent(page, journey, quoteReference); await assent(recipient, providerJourney, quoteReference);
    await page.goto(base + '/conversations/' + journey.thread + '?subject=' + journey.subject); const jobRoute = await page.getByRole('link', { name: 'Consultar trabajo y próximos pasos' }).getAttribute('href');
    await recipient.goto(base + jobRoute.replace(fixture.customer.id, fixture.provider.id));
    assert.equal(await recipient.getByRole('button', { name: 'Comenzar con financiación completa' }).isDisabled(), true);
    assert.ok((await recipient.locator('main').innerText()).includes('Integración pendiente: no se confirmaron fondos'));
    checks.bilateralAgreementPackagedFundingReleasePayoutPending = true;
    await recipient.getByLabel('Descripción', { exact: true }).fill('Explicación privada de ayuda ' + token);
    await click(recipient, recipient.getByRole('button', { name: 'Guardar sin entregar', exact: true }));
    const supportForm = recipient.locator('form[action="/jobs/submit"]'); const supportRevision = await supportForm.locator('input[name=revision]').inputValue();
    await click(recipient, supportForm.locator('button')); await moderate(operator, supportRevision, 'Explicación privada de ayuda ' + token);
    await waitText(page, jobRoute, 'Explicación privada de ayuda ' + token);
    await page.getByRole('link', { name: 'Pedir ayuda o registrar disputa' }).click();
    await click(page, page.getByRole('button', { name: 'Registrar solicitud sin texto público' }));
    assert.ok((await page.locator('main').innerText()).includes('Ayuda sobre el acuerdo conservado')); checks.authoredHelpModeratedStructuralSupportNoMoney = true;
    journey.projection = await capture(page, journey); fixture.journeys.push(journey);
    checks.stage = 'request'; await login(page, fixture.customer); fixture.request = await listing(page, 'REQUEST', 'Restauración instrumento raro ' + token); await moderate(operator, fixture.request.revision);
    await login(recipient, fixture.provider); journey = await respond(recipient, fixture.request, 'SHIPPED'); journey.actor = fixture.provider;
    const proposalReference = await message(recipient, journey, 'Propuesta para restaurar con envío ' + token, true); await moderate(operator, proposalReference.revisionId);
    const requestQuote = await quote(recipient, journey, 'SHIPPED', 'Restaurar instrumento y enviar ' + token); await moderate(operator, requestQuote.revisionId);
    const customerJourney = { ...journey, subject: fixture.customer.id }; await assent(page, customerJourney, requestQuote); await assent(recipient, journey, requestQuote);
    assert.equal((await page.request.get(base + '/listings/' + fixture.request.id)).status(), 403); assert.equal((await page.request.get(base + '/listings/' + fixture.offer.id)).status(), 200);
    journey.projection = await capture(recipient, journey); fixture.journeys.push(journey); checks.oneSelectedRequestReusableOfferPrivateProposalShippedTerms = true;
    checks.stage = 'pickup'; await login(page, fixture.customer);
    await login(recipient, fixture.provider);
    for (const [mode, arrangement] of [['LOCAL', 'PICKUP'], ['SHIPPED', 'PICKUP'], ['LOCAL', 'APPOINTMENT']]) {
      const pickupListing = await listing(recipient, 'OFFER', 'Coordinación ' + mode + ' ' + arrangement + ' ' + token); await moderate(operator, pickupListing.revision);
      const pickupJourney = await respond(page, pickupListing, mode); pickupJourney.actor = fixture.customer;
      const pickupProvider = { ...pickupJourney, subject: fixture.provider.id };
      const pickupQuote = await quote(recipient, pickupProvider, mode, 'Pieza con coordinación ' + arrangement + ' ' + token, arrangement); await moderate(operator, pickupQuote.revisionId);
      await assent(page, pickupJourney, pickupQuote); await assent(recipient, pickupProvider, pickupQuote);
      pickupJourney.projection = await capture(page, pickupJourney); fixture.journeys.push(pickupJourney);
      const expected = arrangement === 'PICKUP' ? 'Retiro' : 'Encuentro presencial';
      const disclosed = pickupJourney.projection.terms.map(term => term.disclosures['Forma de entrega o encuentro']);
      await fs.writeFile(path.join(logs, 'pickup-' + mode + '-' + arrangement + '.json'), JSON.stringify({ mode, arrangement, expected, disclosed }, null, 2), { flag: 'wx' });
      assert.ok(disclosed.includes(expected));
    }
    checks.typedPickupLocalAndShippedAppointmentShippingRemoteWholeTermsDisclosed = true;
    checks.stage = 'guardian'; fixture.minor = await register(page, operator, false, 14); await login(page, fixture.customer);
    await page.goto(base + '/account/guardian'); await page.getByLabel('Identificador de la otra cuenta sintética').fill(fixture.minor.id); await page.getByLabel('Mi papel').selectOption('true');
    await click(page, page.getByRole('button', { name: 'Solicitar relación pendiente' }));
    const relationship = (await page.locator('section.card dd').allTextContents())[0];
    await operator.goto(base + '/admin/accounts'); const relationCard = operator.locator('section.card').filter({ has: operator.getByRole('heading', { name: 'Relación ' + relationship, exact: true }) });
    await relationCard.getByLabel('Decisión sobre autoridad de prueba').selectOption('true'); await click(operator, relationCard.getByRole('button', { name: 'Registrar revisión independiente' }));
    const guardianJourney = await respond(page, fixture.offer, 'REMOTE', fixture.minor.id); guardianJourney.actor = fixture.customer;
    const guardianProvider = { ...guardianJourney, subject: fixture.provider.id }; const guardianQuote = await quote(recipient, guardianProvider, 'REMOTE', 'Acuerdo bajo autoridad sintética ' + token);
    await moderate(operator, guardianQuote.revisionId); await assent(page, guardianJourney, guardianQuote); await assent(recipient, guardianProvider, guardianQuote);
    const guardianMessage = await message(recipient, guardianProvider, 'Historial seguro del pagador original ' + token); await moderate(operator, guardianMessage.revisionId);
    await waitText(page, '/conversations/' + guardianJourney.thread + '?subject=' + fixture.minor.id, 'Historial seguro del pagador original ' + token, '#delivered-messages');
    const historicalFile = await page.locator('#delivered-messages a').first().getAttribute('href');
    const guardianHelp = await page.getByRole('link', { name: 'Ayuda sobre el acuerdo conservado' }).getAttribute('href');
    await page.goto(base + '/account/guardian'); await click(page, page.locator('form[action="/account/guardian/' + relationship + '/revoke"] button'));
    await page.goto(base + '/conversations/' + guardianJourney.thread + '?subject=' + fixture.minor.id);
    assert.equal(await page.locator('form[action="/conversations/messages"]').count(), 0);
    assert.equal((await page.request.get(base + '/conversations/' + guardianJourney.thread + '/poll?subject=' + fixture.minor.id)).status(), 403);
    const historical = await page.request.get(base + historicalFile); assert.equal(historical.status(), 200); assert.equal((await historical.body()).toString(), 'synthetic:clear');
    await page.goto(base + '/jobs/support'); await page.locator('a[href="' + guardianHelp + '"]').click();
    assert.ok((await page.locator('main').innerText()).includes('Historial seguro del pagador original ' + token));
    await mobile(page, 'guardian-history', new URL(page.url()).pathname + new URL(page.url()).search);
    await login(page, fixture.minor); assert.equal((await page.request.get(base + historicalFile)).status(), 403); checks.revokedGuardianRetainedExactPayerHistoryFilesMinorDenied = true;
    checks.stage = 'captureSuppression'; await login(page, fixture.customer);
    const capturedReference = { ownerKind: 'OFFER_BODY', resourceId: fixture.offer.id, revisionId: fixture.offer.revision };
    await decide(operator, capturedReference, 'CORRECT_LABEL', 'ADULT');
    await page.goto(base + '/account'); await page.getByLabel('Preferencia adulta (requiere evidencia adulta vigente)').uncheck(); await click(page, page.getByRole('button', { name: 'Guardar preferencias' }));
    await page.goto(base + '/conversations/' + fixture.journeys[0].thread + '?subject=' + fixture.customer.id);
    assert.equal(await page.locator('details pre.authored').count(), 0); assert.ok(!(await page.locator('main').innerText()).includes(fixture.offer.title));
    await page.goto(base + '/conversations'); assert.ok(!(await page.locator('main').innerText()).includes(fixture.offer.title));
    await page.goto(base + '/conversations/' + guardianJourney.thread + '?subject=' + fixture.minor.id); assert.equal(await page.locator('details pre.authored').count(), 0);
    await page.goto(base + guardianHelp); assert.ok((await page.locator('main').innerText()).includes('ARS 123.45'));
    await decide(operator, capturedReference, 'CORRECT_LABEL', 'GENERAL');
    await page.goto(base + '/conversations/' + fixture.journeys[0].thread + '?subject=' + fixture.customer.id); assert.ok((await page.locator('main').innerText()).includes(fixture.offer.title));
    await decide(operator, capturedReference, 'RECALL');
    await page.goto(base + '/conversations/' + fixture.journeys[0].thread + '?subject=' + fixture.customer.id); assert.equal(await page.locator('details pre.authored').count(), 0);
    await page.goto(base + guardianHelp);
    fixture.guardianHistory = { threadRoute: '/conversations/' + guardianJourney.thread + '?subject=' + fixture.minor.id, helpRoute: guardianHelp,
      file: historicalFile, text: (await page.locator('main').innerText()).replace(/\s+/g, ' ').trim(), sha256: crypto.createHash('sha256').update(await (await page.request.get(base + historicalFile)).body()).digest('hex') };
    checks.currentLabelPreferenceGuardianRecallNeutralContextHelp = true;
    fixture.offerRecalled = true;
    for (const entry of fixture.journeys) { await login(page, entry.actor); entry.projection = await capture(page, entry); }
    checks.stage = 'stranger'; fixture.stranger = await register(page, operator, false);
    assert.equal((await page.request.get(base + '/conversations/' + fixture.journeys[0].thread + '?subject=' + fixture.stranger.id)).status(), 403);
    assert.equal((await page.request.post(base + '/conversations/respond', { form: { listing: fixture.offer.id, subject: fixture.stranger.id, mode: 'REMOTE' } })).status(), 403);
    checks.currentMembershipAndCsrfDenials = true;
    await login(page, fixture.customer);
    for (const [name, route] of [['thread', '/conversations/' + fixture.journeys[0].thread + '?subject=' + fixture.customer.id], ['inbox', '/conversations'], ['job', fixture.journeys[0].projection.jobRoute], ['notices', '/notifications'], ['agreement', '/agreements/new?kind=CHANGE&job=' + fixture.journeys[0].projection.jobRoute.split('/')[2].split('?')[0] + '&subject=' + fixture.customer.id], ['public-own', publicReference.ownRoute]]) await mobile(page, name, route);
    checks.keyboardFocusSpanishStagedFormsMobile = true; assert.equal(checks.pageError, undefined);
    await fs.writeFile(fixturePath, JSON.stringify(fixture), { mode: 0o600, flag: 'wx' });
    await fs.writeFile(path.join(logs, 'qa.json'), JSON.stringify(checks, null, 2)); console.log(JSON.stringify(checks));
  } finally { for (const context of contexts) await context.close(); await browser.close(); }
}
run().catch(async error => { checks.failure = error.name; checks.frames = (error.stack || '').split('\n').filter(line => line.trim().startsWith('at ')).slice(0, 3); await fs.mkdir(logs, { recursive: true }); await fs.writeFile(path.join(logs, 'failure-' + Date.now() + '.json'), JSON.stringify(checks, null, 2)); console.error(error.name); process.exitCode = 1; });
