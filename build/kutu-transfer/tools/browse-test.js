// Runs the served page in a real DOM with a fake server behind it, and drives it with real
// clicks. curl never touches this code, and the one genuine defect in the first version lived
// exactly here, so the page gets executed rather than read.
const fs = require('fs');
const { JSDOM } = require('jsdom');

const html = fs.readFileSync('app.html', 'utf8');

// ---------------------------------------------------------------- the fake box
const FOLDERS = {
  '': {
    path: '', parent: null, writable: false, card: true, free: 2850000000,
    crumbs: [],
    entries: [
      { n: 'Kutu Klasörü', p: 'kutu', d: true, s: 0, t: 0 },
      { n: 'Dahili Depolama', p: 'sd', d: true, s: 0, t: 0 }
    ]
  },
  'sd': {
    path: 'sd', parent: '', writable: true, card: true, denied: false, truncated: false,
    free: 2850000000,
    crumbs: [{ p: 'sd', n: 'Dahili Depolama' }],
    entries: [
      // hostile on purpose: quotes, angle brackets, ampersand, apostrophe, percent, hash
      { n: "it's & <b>bold</b> #1", p: "sd/it's & <b>bold</b> #1", d: true, s: 0, t: 1 },
      { n: 'Download', p: 'sd/Download', d: true, s: 0, t: 2 },
      { n: '<img src=x onerror=alert(1)>&"\'.txt', p: 'sd/<img src=x onerror=alert(1)>&"\'.txt',
        d: false, s: 1234, t: 3 },
      { n: 'film.mp4', p: 'sd/film.mp4', d: false, s: 1200000000, t: 4 }
    ]
  },
  'sd/Download': {
    path: 'sd/Download', parent: 'sd', writable: true, card: true, free: 2850000000,
    crumbs: [{ p: 'sd', n: 'Dahili Depolama' }, { p: 'sd/Download', n: 'Download' }],
    entries: []
  }
};

const seen = [];           // every request the page makes
let xhrOpened = null;

function fakeFetch(url, opts) {
  seen.push((opts && opts.method ? opts.method + ' ' : 'GET ') + url);
  const m = /^\/api\/list\?p=(.*)$/.exec(url);
  if (m) {
    const p = decodeURIComponent(m[1]);
    const f = FOLDERS[p];
    if (!f) return Promise.resolve({ status: 404, json: () => Promise.resolve({}) });
    return Promise.resolve({ status: 200, ok: true, json: () => Promise.resolve(f) });
  }
  return Promise.resolve({ status: 200, ok: true, json: () => Promise.resolve({}) });
}

// ---------------------------------------------------------------- run it
const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  url: 'http://192.168.61.115:8787/',
  beforeParse(w) {
    w.fetch = fakeFetch;
    w.confirm = () => true;
    w.XMLHttpRequest = function () {
      this.upload = {};
      this.open = (method, url) => { xhrOpened = method + ' ' + url; };
      this.send = () => { this.status = 200; if (this.onload) this.onload(); };
    };
  }
});
const w = dom.window;
const doc = w.document;

const fails = [];
function check(name, cond, extra) {
  console.log((cond ? '  ok   ' : '  FAIL ') + name + (extra ? '   ' + extra : ''));
  if (!cond) fails.push(name);
}
const wait = () => new Promise(r => setTimeout(r, 40));
function rows() { return [...doc.querySelectorAll('#list .row')]; }
function click(el) { el.dispatchEvent(new w.MouseEvent('click', { bubbles: true })); }

(async () => {
  await wait();

  console.log('\n-- root list');
  check('two roots listed', rows().length === 2, rows().map(r => r.textContent).join(' | '));
  check('upload hidden at root', doc.getElementById('drop').style.display === 'none');
  check('where says pick a folder',
    /klasör seç/.test(doc.getElementById('where').textContent));
  check('free line rendered', /2\.7 GB/.test(doc.getElementById('free').textContent),
    doc.getElementById('free').textContent);

  console.log('\n-- navigate into a root by clicking the row');
  click(rows()[1]);
  await wait();
  check('fetched sd', seen.includes('GET /api/list?p=sd'), seen[seen.length - 1]);
  check('crumb trail built', doc.getElementById('crumbs').textContent.replace(/\s+/g, ' ')
    .indexOf('Kutu/Dahili Depolama') === 0,
    JSON.stringify(doc.getElementById('crumbs').textContent));
  check('upload shown and targeted',
    doc.getElementById('drop').style.display === '' &&
    /Dahili Depolama/.test(doc.getElementById('where').textContent),
    doc.getElementById('where').textContent);

  console.log('\n-- escaping');
  const listHtml = doc.getElementById('list').innerHTML;
  check('no live <img> injected', doc.querySelectorAll('#list img').length === 0);
  check('payload present as text',
    doc.getElementById('list').textContent.includes('<img src=x onerror=alert(1)>'));
  check('no live <b> from folder name', doc.querySelectorAll('#list b').length === 0);
  check('rows: up + 4 entries', rows().length === 5, 'got ' + rows().length);

  console.log('\n-- download link for the hostile name');
  const a = [...doc.querySelectorAll('#list a')].find(x => x.getAttribute('href').startsWith('/dl'));
  const href = a.getAttribute('href');
  const back = decodeURIComponent(href.slice('/dl?f='.length));
  check('href round-trips to the exact path', back === 'sd/<img src=x onerror=alert(1)>&"\'.txt',
    JSON.stringify(back));

  console.log('\n-- delete inside a folder row must delete, not navigate');
  const dirRow = rows().find(r => r.textContent.includes("it's &"));
  const before = seen.length;
  click(dirRow.querySelector('button'));
  await wait();
  const rmCall = seen.slice(before).find(s => s.startsWith('POST /rm'));
  check('a /rm was sent', !!rmCall, rmCall);
  check('/rm path round-trips',
    rmCall && decodeURIComponent(rmCall.slice('POST /rm?f='.length)) === "sd/it's & <b>bold</b> #1",
    rmCall);
  check('did not navigate away', !seen.slice(before).some(s => /api\/list\?p=sd%2Fit/.test(s)));

  console.log('\n-- clicking the folder name still navigates');
  click(rows().find(r => r.textContent.includes('Download')));
  await wait();
  check('navigated into Download', seen.includes('GET /api/list?p=sd%2FDownload'));
  check('empty folder says so', /Bu klasör boş/.test(doc.getElementById('list').textContent));
  check('hash tracks the folder', decodeURIComponent(w.location.hash.slice(1)) === 'sd/Download',
    w.location.hash);

  console.log('\n-- upload targets the folder we are standing in');
  const files = [{ name: 'x.bin', size: 5 }];
  w.eval('send')(files);
  await wait();
  check('XHR posts to the current folder', xhrOpened === 'POST /up?p=sd%2FDownload', xhrOpened);

  console.log('\n-- up one level');
  click(rows()[0]);
  await wait();
  check('went back to sd', seen[seen.length - 1] === 'GET /api/list?p=sd', seen[seen.length - 1]);

  w.close();
  console.log('\n' + (fails.length ? 'FAILED: ' + fails.join(', ') : 'all checks passed'));
  process.exit(fails.length ? 1 : 0);
})();
