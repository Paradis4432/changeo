const { firefox } = require('/home/paradis/changeo/.local-tools/browser/node_modules/playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');

const root = '/home/paradis/changeo';
const logs = path.join(root, 'docs/agent-work/changeo-foundations/reviewer/logs');
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
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000) + 1));
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
  const context = await browser.newContext(); const page = await context.newPage();
  try {
    const material = Object.fromEntries((await fs.readFile(path.join(privateDirectory, 'operator-enrollment.private'), 'utf8')).split('\n').filter(line => line.includes('=')).map(line => {const i=line.indexOf('=');return [line.slice(0,i),line.slice(i+1)];}));
    await login(page, material.contact, material.password);
    await page.goto(baseUrl + '/admin/mfa');
    let data;
    if (process.argv.includes('--after')) {
      data=JSON.parse(await fs.readFile(path.join(privateDirectory,'review-replay.private'),'utf8'));
      assert.ok(Math.abs(Math.floor(Date.now()/30000)-data.counter)<=1,'Replay check must remain in valid TOTP window');
    } else { data={counter:Math.floor(Date.now()/30000)+1}; data.code=totp(material.totpBase32); }
    await page.getByLabel('Código de seis dígitos').fill(data.code); await page.getByRole('button',{name:'Verificar MFA'}).click();
    if (process.argv.includes('--after')) {
      assert.equal(await page.getByText('Código inválido, vencido o utilizado',{exact:true}).count(),1);console.log('Persisted replay denied across actual application restart while code remains within accepted time window');
    } else {await page.waitForURL('**/admin/reauth');await privateJson('review-replay.private',data);console.log('Unique future-window TOTP accepted; private replay fixture stored without output');}
  } finally {await context.close();await browser.close();}
}
run().catch(error=>{console.error(error.name+': '+error.message);process.exitCode=1;});
