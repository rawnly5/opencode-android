const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const NODE_DIR = __dirname;
const MODULES_DIR = path.join(NODE_DIR, 'node_modules');
const OPENCODE_HOME = process.env.OPENCODE_HOME || path.join(NODE_DIR, '.opencode');
const PORT_FILE = path.join(NODE_DIR, '.opencode-port');

function findOpenCodeBin() {
  const candidates = [
    path.join(MODULES_DIR, '.bin', 'opencode'),
    path.join(MODULES_DIR, 'opencode-ai', 'bin', 'opencode'),
    path.join(MODULES_DIR, 'opencode-ai', 'dist', 'cli.js'),
  ];

  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }

  try {
    const resolved = require.resolve('opencode-ai');
    const pkgDir = path.dirname(resolved);
    const binPath = path.join(pkgDir, 'bin', 'opencode');
    if (fs.existsSync(binPath)) return binPath;

    const distCli = path.join(pkgDir, 'dist', 'cli.js');
    if (fs.existsSync(distCli)) return distCli;

    return resolved;
  } catch (e) {
    return null;
  }
}

function ensureDirs() {
  const dirs = [
    OPENCODE_HOME,
    path.join(OPENCODE_HOME, 'projects'),
    path.join(OPENCODE_HOME, 'config'),
    path.join(OPENCODE_HOME, 'data'),
    path.join(OPENCODE_HOME, 'logs'),
  ];

  for (const dir of dirs) {
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
  }
}

function tryNpmInstall() {
  if (fs.existsSync(MODULES_DIR)) {
    const binDir = path.join(MODULES_DIR, '.bin');
    if (fs.existsSync(binDir)) {
      const items = fs.readdirSync(binDir);
      if (items.some(i => i.includes('opencode'))) {
        return true;
      }
    }
  }

  console.log('[setup] opencode not found, running npm install...');

  try {
    const npmResult = spawn.sync(
      process.execPath,
      [
        '-e',
        `
        const { execSync } = require('child_process');
        try {
          execSync('npm install --production --no-optional', {
            cwd: '${NODE_DIR.replace(/'/g, "'\\''")}',
            stdio: 'inherit',
            env: { ...process.env, NODE_ENV: 'production' }
          });
          process.exit(0);
        } catch(e) {
          process.exit(1);
        }
        `,
      ],
      {
        cwd: NODE_DIR,
        stdio: ['pipe', 'inherit', 'inherit'],
        env: { ...process.env, NODE_ENV: 'production' },
        shell: true,
      }
    );

    if (npmResult.status !== 0) {
      console.log('[setup] npm install had issues, trying alternative...');
      const npmResult2 = spawn.sync('npm', ['install', '--production'], {
        cwd: NODE_DIR,
        stdio: 'inherit',
        env: { ...process.env, NODE_ENV: 'production' },
        shell: true,
      });
      if (npmResult2.status !== 0) {
        console.error('[setup] npm install failed completely');
        return false;
      }
    }

    return true;
  } catch (e) {
    console.error('[setup] npm install error:', e.message);
    return false;
  }
}

function waitForModules() {
  if (fs.existsSync(MODULES_DIR)) {
    const binDir = path.join(MODULES_DIR, '.bin');
    if (fs.existsSync(binDir)) {
      const items = fs.readdirSync(binDir);
      if (items.some(i => i.includes('opencode'))) {
        return true;
      }
    }
  }

  for (let i = 0; i < 60; i++) {
    const binDir = path.join(MODULES_DIR, '.bin');
    if (fs.existsSync(binDir)) {
      const items = fs.readdirSync(binDir);
      if (items.some(i => i.includes('opencode'))) {
        return true;
      }
    }
    spawn.sync('sleep', ['1'], { shell: true });
  }

  return false;
}

function startOpenCode(binPath) {
  const isJsFile = binPath.endsWith('.js');
  const command = isJsFile ? process.execPath : binPath;
  const args = isJsFile ? [binPath] : [];
  args.push('web', '--port', '0', '--hostname', '127.0.0.1');

  const proc = spawn(command, args, {
    cwd: OPENCODE_HOME,
    stdio: ['pipe', 'pipe', 'pipe'],
    env: {
      ...process.env,
      NODE_ENV: 'production',
      OPENCODE_HOME: OPENCODE_HOME,
      HOME: process.env.HOME || NODE_DIR,
    },
    shell: false,
  });

  let ready = false;

  proc.stdout.on('data', (data) => {
    const text = data.toString();
    process.stdout.write(text);

    if (!ready) {
      const portMatch = text.match(/https?:\/\/127\.0\.0\.1[:\/](\d+)/);
      if (portMatch) {
        ready = true;
        const port = parseInt(portMatch[1], 10);
        try {
          fs.writeFileSync(PORT_FILE, String(port));
        } catch (e) {
          console.error('Failed to write port file:', e.message);
        }
        console.log(`\n[opencode] Server ready on port ${port}`);
      }
    }
  });

  proc.stderr.on('data', (data) => {
    process.stderr.write(data);
  });

  proc.on('exit', (code) => {
    console.log(`[opencode] Process exited with code ${code}`);
    if (fs.existsSync(PORT_FILE)) {
      try { fs.unlinkSync(PORT_FILE); } catch (e) {}
    }
    process.exit(code || 0);
  });

  proc.on('error', (err) => {
    console.error('[opencode] Failed to start:', err.message);
    process.exit(1);
  });
}

function main() {
  ensureDirs();

  if (!tryNpmInstall()) {
    console.error('[fatal] Could not install opencode dependencies');
    process.exit(1);
  }

  if (!waitForModules()) {
    console.error('[fatal] opencode not found after npm install');
    process.exit(1);
  }

  const binPath = findOpenCodeBin();
  if (!binPath) {
    console.error('[fatal] Could not find opencode executable');
    process.exit(1);
  }

  console.log(`[opencode] Starting from: ${binPath}`);
  startOpenCode(binPath);
}

main();
