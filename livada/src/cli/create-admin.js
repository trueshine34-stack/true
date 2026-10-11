// Creează un cont de administrator/manager sau resetează parola unui cont existent.
//   npm run create-admin -- ana@livada.ro                 → cere parola în terminal
//   npm run create-admin -- ana@livada.ro --role manager --name "Ana Ionescu"
//   ADMIN_NEW_PASSWORD=... npm run create-admin -- ana@livada.ro   (fără prompt, pentru scripturi)
import readline from 'node:readline';
import { openDb, one, run, closeDb } from '../db/index.js';
import { hashPassword } from '../lib/security.js';
import { isEmail, passwordProblem } from '../lib/validate.js';

const args = process.argv.slice(2);
const flag = (name, def) => { const i = args.indexOf(`--${name}`); return i >= 0 ? args[i + 1] : def; };
const email = args.find((a) => !a.startsWith('--') && isEmail(a));
const role = flag('role', 'admin');
const [first = 'Administrator', ...rest] = String(flag('name', '')).trim().split(/\s+/).filter(Boolean);
const last = rest.join(' ') || (flag('name') ? '' : 'Livada');

if (!email || !['admin', 'manager'].includes(role)) {
  console.error('Utilizare: npm run create-admin -- <email> [--role admin|manager] [--name "Prenume Nume"]');
  process.exit(1);
}

function ask(question) {
  if (!process.stdin.isTTY) {
    return new Promise((resolve) => { let s = ''; process.stdin.on('data', (d) => { s += d; }).on('end', () => resolve(s.split('\n')[0].trim())); });
  }
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  // ascunde parola în timp ce e tastată
  rl._writeToOutput = function (s) { rl.output.write(s.startsWith(question) ? s : '*'); };
  return new Promise((resolve) => rl.question(question, (a) => { rl.close(); process.stdout.write('\n'); resolve(a.trim()); }));
}

const password = process.env.ADMIN_NEW_PASSWORD || await ask('Parolă nouă (min. 8 caractere, litere și cifre): ');
const problem = passwordProblem(password);
if (problem) { console.error(`✗ ${problem}`); process.exit(1); }

openDb();
const hash = await hashPassword(password);
const existing = one('SELECT id, role FROM users WHERE email = ? COLLATE NOCASE', email);
if (existing) {
  run("UPDATE users SET password_hash = ?, role = ?, status = 'active', email_verified_at = COALESCE(email_verified_at, datetime('now')) WHERE id = ?", hash, role, existing.id);
  run('DELETE FROM sessions WHERE user_id = ?', existing.id);
  console.log(`✓ ${email}: rol „${role}”, parolă resetată, sesiunile vechi închise.`);
} else {
  run(`INSERT INTO users(email, password_hash, first_name, last_name, role, email_verified_at, admin_lang)
       VALUES (?,?,?,?,?, datetime('now'), 'ro')`, email.toLowerCase(), hash, first, last, role);
  console.log(`✓ cont ${role} creat: ${email}`);
}
closeDb();
