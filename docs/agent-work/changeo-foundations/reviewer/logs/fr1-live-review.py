import http.client,json,pathlib,re,urllib.parse,uuid,secrets
out=pathlib.Path('docs/agent-work/changeo-foundations/reviewer/logs'); results={}
def get(path,source,cookie=None):
 c=http.client.HTTPConnection('127.0.0.1',8080,source_address=(source,0));c.request('GET',path,headers={'Cookie':cookie} if cookie else {});r=c.getresponse();html=r.read().decode();newcookie=r.getheader('Set-Cookie');c.close();return (newcookie.split(';')[0] if newcookie else cookie),re.search(r'name="_csrf" value="([^"]+)"',html).group(1)
def post(path,source,data,cookie,csrf):
 c=http.client.HTTPConnection('127.0.0.1',8080,source_address=(source,0));c.request('POST',path,urllib.parse.urlencode({**data,'_csrf':csrf}),{'Cookie':cookie,'Content-Type':'application/x-www-form-urlencoded'});r=c.getresponse();html=r.read().decode();record={'status':r.status,'location':r.getheader('Location'),'limited':'Demasiados intentos' in html,'password_checked':'No se pudo autenticar' in html};newcookie=r.getheader('Set-Cookie');c.close();return record,(newcookie.split(';')[0] if newcookie else cookie)
def anonymous(path,source,data):
 cookie,csrf=get(path,source);return post(path,source,data,cookie,csrf)[0]
def alias(contact,n):return ' '*n+(contact.upper() if n%2 else contact)+' '
fixture=json.loads(pathlib.Path('.runtime/browser-fixture.private').read_text());contact=fixture['contact']
rows=[anonymous('/login',f'127.0.0.{n+10}',{'username':alias(contact,n),'password':'Invalid-password-for-rate-probe'}) for n in range(1,13)]
assert all(x['location'].endswith('/login?error') for x in rows[:10]);assert all(x['location'].endswith('/login?limited') for x in rows[10:]);results['canonical_login_account']={'attempts':12,'allowed_checks':10,'limited':2}
rows=[anonymous('/password/forgot',f'127.0.0.{n+40}',{'contact':alias(contact,n)}) for n in range(1,7)]
assert all(x['status']==302 for x in rows[:5]);assert rows[5]['status']==403 and rows[5]['limited'];results['canonical_recovery_account']={'attempts':6,'acknowledged':5,'limited':1}
accounts=[]
for n in range(10):
 value={'contact':'review'+str(uuid.uuid4())+'@example.test','password':secrets.token_urlsafe(24)};r=anonymous('/register',f'127.0.0.{n+150}',value);assert r['status']==302;accounts.append(value)
rows=[anonymous('/login','127.0.0.140',{'username':a['contact'],'password':'Invalid-password-for-rate-probe'}) for a in accounts];assert all(x['location'].endswith('/login?error') for x in rows)
r=anonymous('/login','127.0.0.140',{'username':'unused'+str(uuid.uuid4())+'@example.test','password':'Invalid-password-for-rate-probe'});assert r['location'].endswith('/login?limited');results['login_source']={'distinct_known_accounts':10,'limited_next':True}
rows=[anonymous('/password/forgot','127.0.0.140',{'contact':a['contact']}) for a in accounts[:5]];assert all(x['status']==302 for x in rows)
r=anonymous('/password/forgot','127.0.0.140',{'contact':accounts[5]['contact']});assert r['status']==403 and r['limited'];results['recovery_source']={'distinct_known_accounts':5,'limited_next':True}
material=dict(line.split('=',1) for line in pathlib.Path('.runtime/operator-enrollment.private').read_text().splitlines() if '=' in line)
def login(value,source):
 cookie,csrf=get('/login',source);r,cookie=post('/login',source,{'username':value['contact'],'password':value['password']},cookie,csrf);assert r['status']==302 and r['location'].endswith('/account');return cookie
cookie=login(material,'127.0.0.70');rows=[]
for n in range(6):
 source=f'127.0.0.{n+80}';cookie,csrf=get('/admin/reauth',source,cookie);r,cookie=post('/admin/reauth',source,{'password':material['password'] if n==5 else 'Invalid-password-for-rate-probe'},cookie,csrf);rows.append(r)
assert all(x['password_checked'] and not x['limited'] for x in rows[:5]);assert rows[5]['limited'] and not rows[5]['password_checked'];results['reauth_actor']={'attempts':6,'password_checks':5,'limited':1,'correct_password_blocked':True}
rows=[]
for n,a in enumerate(accounts[:6]):
 cookie=login(a,f'127.0.0.{n+180}');cookie,csrf=get('/admin/reauth','127.0.0.200',cookie);r,cookie=post('/admin/reauth','127.0.0.200',{'password':a['password'] if n==5 else 'Invalid-password-for-rate-probe'},cookie,csrf);rows.append(r)
assert all(x['password_checked'] and not x['limited'] for x in rows[:5]);assert rows[5]['limited'] and not rows[5]['password_checked'];results['reauth_source']={'distinct_actors':6,'password_checks':5,'limited':1,'correct_password_blocked':True}
(out/'fr1-live-review.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
