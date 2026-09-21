package local.kutu.transfer;

/**
 * The whole browser UI, as one self-contained page.
 *
 * Inline rather than in assets so there is exactly one request to serve and no asset routing,
 * no cache questions and nothing that can be fetched without the session cookie. It is written
 * to work on a phone held in one hand as readily as on a laptop.
 *
 * The page is a folder browser, not a list. Every clickable thing carries its target in a
 * data- attribute and one delegated listener reads it back, so no path is ever interpolated
 * into a quoted JavaScript string. That is deliberate: the one real defect found in the first
 * version was exactly that - an apostrophe in a file name closing an inline onclick string -
 * and this shape cannot have it.
 */
final class Page {

    private Page() {
    }

    static byte[] html(boolean authed) {
        try {
            return (authed ? APP : GATE).getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static final String HEAD =
            "<!doctype html><html lang=\"tr\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>Kutu Aktarım</title><style>"
            + ":root{--bg:#f4f6fa;--card:#fff;--ink:#16233a;--dim:#5b6b82;--line:#e2e8f2;"
            + "--accent:#2f7fb8;--bad:#c0392b}"
            + "@media(prefers-color-scheme:dark){:root{--bg:#0f1622;--card:#182739;--ink:#e8eef6;"
            + "--dim:#94a5bb;--line:#26364b;--accent:#7fc4f5}}"
            + "*{box-sizing:border-box}"
            + "body{margin:0;background:var(--bg);color:var(--ink);"
            + "font:16px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}"
            + ".wrap{max-width:720px;margin:0 auto;padding:24px 16px 64px}"
            + "h1{font-size:20px;margin:0 0 4px}"
            + ".sub{color:var(--dim);font-size:14px;margin-bottom:14px}"
            + ".card{background:var(--card);border:1px solid var(--line);border-radius:16px;"
            + "padding:16px;margin-bottom:14px}"
            + "input,button{font:inherit}"
            + "input[type=password],input[type=text]{width:100%;padding:12px 14px;"
            + "border:1px solid var(--line);border-radius:12px;background:transparent;"
            + "color:var(--ink);text-align:center;letter-spacing:.4em;font-size:22px}"
            + "button{padding:12px 18px;border:0;border-radius:12px;background:var(--accent);"
            + "color:#fff;font-weight:600;cursor:pointer;width:100%}"
            + "button.sm{width:auto;padding:7px 12px;font-size:13px;background:transparent;"
            + "color:var(--dim);border:1px solid var(--line)}"
            + "#drop{border:2px dashed var(--line);border-radius:16px;padding:22px 16px;"
            + "text-align:center;color:var(--dim);cursor:pointer}"
            + "#drop.hot{border-color:var(--accent);color:var(--accent)}"
            + "#where{font-size:13px;color:var(--dim);margin-bottom:10px}"
            + "#where b{color:var(--ink)}"
            // the trail sits above the list and wraps rather than scrolling sideways
            + "#crumbs{font-size:14px;color:var(--dim);margin-bottom:12px;word-break:break-word}"
            + "#crumbs a{color:var(--accent);text-decoration:none}"
            + "#crumbs .sep{opacity:.5;padding:0 4px}"
            + "#crumbs .here{color:var(--ink);font-weight:600}"
            + ".row{display:flex;align-items:center;gap:10px;padding:10px 0;"
            + "border-bottom:1px solid var(--line)}"
            + ".row:last-child{border-bottom:0}"
            + ".row.dir{cursor:pointer}"
            + ".ic{width:22px;text-align:center;flex:none;opacity:.85}"
            + ".nm{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
            + ".nm a{display:block;overflow:hidden;text-overflow:ellipsis}"
            + ".mt{color:var(--dim);font-size:12px}"
            + "a{color:var(--accent);text-decoration:none}"
            + ".err{color:var(--bad);font-size:14px;margin-top:10px;min-height:20px}"
            + ".bar{height:4px;background:var(--line);border-radius:2px;overflow:hidden;"
            + "margin-top:12px}"
            + ".bar i{display:block;height:100%;width:0;background:var(--accent)}"
            + "</style></head><body><div class=\"wrap\">";

    private static final String GATE = HEAD
            + "<h1>Kutu Aktarım</h1>"
            + "<div class=\"sub\">Televizyondaki 6 haneli kodu gir</div>"
            + "<div class=\"card\">"
            + "<input id=\"p\" type=\"password\" inputmode=\"numeric\" maxlength=\"6\" "
            + "autocomplete=\"off\" placeholder=\"······\">"
            + "<div class=\"err\" id=\"e\"></div>"
            + "<button onclick=\"go()\">Bağlan</button>"
            + "</div><script>"
            + "var p=document.getElementById('p'),e=document.getElementById('e');p.focus();"
            + "p.addEventListener('keydown',function(k){if(k.key==='Enter')go()});"
            + "function go(){e.textContent='';"
            + "fetch('/auth',{method:'POST',body:'pin='+encodeURIComponent(p.value)})"
            + ".then(function(r){if(r.ok){location.reload();return}"
            + "return r.json().then(function(j){"
            + "e.textContent=j.error==='locked'"
            + "?'Çok fazla deneme. Televizyondan aktarımı kapatıp yeniden aç.'"
            + ":'Kod hatalı';p.value='';p.focus()})})"
            + ".catch(function(){e.textContent='Bağlantı kurulamadı'})}"
            + "</script></div></body></html>";

    private static final String APP = HEAD
            + "<h1>Kutu Aktarım</h1>"
            + "<div class=\"sub\" id=\"free\">&nbsp;</div>"
            + "<div class=\"card\">"
            + "<div id=\"where\">&nbsp;</div>"
            + "<div id=\"drop\">Dosyaları buraya sürükle"
            + "<br><span class=\"mt\">veya dokunup seç</span>"
            + "<div class=\"bar\"><i id=\"pb\"></i></div></div>"
            + "<input id=\"f\" type=\"file\" multiple hidden>"
            + "<div class=\"err\" id=\"e\"></div></div>"
            + "<div class=\"card\"><div id=\"crumbs\"></div>"
            + "<div id=\"list\">Yükleniyor…</div></div>"
            + "<script>"
            + "var drop=document.getElementById('drop'),fi=document.getElementById('f'),"
            + "e=document.getElementById('e'),pb=document.getElementById('pb'),"
            + "list=document.getElementById('list'),free=document.getElementById('free'),"
            + "crumbs=document.getElementById('crumbs'),where=document.getElementById('where');"

            // '' is the root list: the places the TV is willing to show, not a real folder.
            + "var cur='',sig='',writable=false;"

            + "function hs(n){if(n<1024)return n+' B';var u=['KB','MB','GB','TB'],i=-1;"
            + "do{n/=1024;i++}while(n>=1024&&i<3);return n.toFixed(1)+' '+u[i]}"
            + "function esc(s){return String(s).replace(/[&<>\"]/g,function(c){"
            + "return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]})}"

            // Paths travel through the DOM percent-encoded and come back decoded. Nothing is
            // ever pasted into a quoted JS string, so no character in a file name can break out.
            + "function attr(p){return esc(encodeURIComponent(p))}"
            + "function readTarget(node,key){var t=node;"
            + "while(t&&t!==document.body){if(t.getAttribute){var v=t.getAttribute(key);"
            + "if(v!==null)return decodeURIComponent(v)}t=t.parentNode}return null}"

            + "function nav(p){if(p===cur)return;cur=p;sig='';e.textContent='';"
            + "try{location.hash=encodeURIComponent(p)}catch(x){}refresh()}"

            + "window.onhashchange=function(){var p='';"
            + "try{p=decodeURIComponent((location.hash||'').substring(1))}catch(x){p=''}"
            + "if(p!==cur){cur=p;sig='';refresh()}};"

            + "function refresh(){fetch('/api/list?p='+encodeURIComponent(cur))"
            + ".then(function(r){if(r.status===401){location.reload();return null}"
            + "if(r.status===404){if(cur!==''){nav('')}return null}return r.json()})"
            + ".then(function(j){if(j)render(j)}).catch(function(){})}"

            + "function render(j){"
            + "var k=j.path+'|'+j.free+'|'+j.writable+'|'+j.denied+'|'+j.entries.length+'|';"
            + "for(var i=0;i<j.entries.length;i++){var x=j.entries[i];"
            + "k+=x.n+':'+x.s+':'+x.t+':'+x.d+'|'}"
            + "if(k===sig)return;sig=k;"
            + "cur=j.path;writable=!!j.writable;"

            + "free.textContent='Kutuda '+hs(j.free)+' boş yer var';"

            // trail: "Kutu / Dahili Depolama / Download", every step clickable but the last
            + "var cb='<a href=\"#\" data-go=\"\">Kutu</a>';"
            + "for(var c=0;c<j.crumbs.length;c++){var cr=j.crumbs[c];"
            + "cb+='<span class=\"sep\">/</span>';"
            + "cb+=c===j.crumbs.length-1?'<span class=\"here\">'+esc(cr.n)+'</span>'"
            + ":'<a href=\"#\" data-go=\"'+attr(cr.p)+'\">'+esc(cr.n)+'</a>'}"
            + "crumbs.innerHTML=cb;"

            + "if(j.path===''){drop.style.display='none';"
            + "where.innerHTML=j.card?'Yüklemek için bir klasör seç'"
            + ":'Yüklemek için bir klasör seç · <span class=\"mt\">televizyonda depolama izni "
            + "verilmediği için yalnızca Kutu klasörü görünüyor</span>'}"
            + "else if(!j.writable){drop.style.display='none';"
            + "where.innerHTML='Bu klasör salt okunur'}"
            + "else{drop.style.display='';"
            + "where.innerHTML='Yükleme hedefi: <b>'+esc(j.crumbs[j.crumbs.length-1].n)+'</b>'}"

            + "var h='';"
            + "if(j.path!==''){h+='<div class=\"row dir\" data-go=\"'+attr(j.parent)+'\">'"
            + "+'<span class=\"ic\">↩</span><div class=\"nm\">Üst klasör</div></div>'}"
            + "if(j.denied){h+='<div class=\"row\"><div class=\"nm mt\">"
            + "Bu klasör okunamıyor</div></div>'}"
            + "for(var i2=0;i2<j.entries.length;i2++){var y=j.entries[i2];var ep=attr(y.p);"
            + "var del=writable?'<button class=\"sm\" data-rm=\"'+ep+'\">Sil</button>':'';"
            + "if(y.d){h+='<div class=\"row dir\" data-go=\"'+ep+'\">'"
            + "+'<span class=\"ic\">📁</span><div class=\"nm\">'+esc(y.n)+'</div>'+del+'</div>'}"
            + "else{h+='<div class=\"row\"><span class=\"ic\">📄</span>'"
            + "+'<div class=\"nm\"><a href=\"/dl?f='+ep+'\">'+esc(y.n)+'</a>'"
            + "+'<div class=\"mt\">'+hs(y.s)+'</div></div>'+del+'</div>'}}"
            + "if(j.truncated){h+='<div class=\"row\"><div class=\"nm mt\">"
            + "Çok fazla öğe var — ilk 2000 tanesi gösteriliyor</div></div>'}"
            + "if(!j.entries.length&&!j.denied){h+='<div class=\"row\">"
            + "<div class=\"nm mt\">Bu klasör boş</div></div>'}"
            + "list.innerHTML=h}"

            // data-rm is looked for first: a folder row carries data-go and contains the Sil
            // button, so asking about navigation first would swallow every folder delete.
            + "function click(ev){var r=readTarget(ev.target,'data-rm');"
            + "if(r!==null){ev.preventDefault();rm(r);return}"
            + "var g=readTarget(ev.target,'data-go');"
            + "if(g!==null){ev.preventDefault();nav(g)}}"
            + "list.addEventListener('click',click);crumbs.addEventListener('click',click);"

            + "function rm(p){var nm=p.substring(p.lastIndexOf('/')+1);"
            + "if(!confirm(nm+'\\nsilinsin mi?'))return;e.textContent='';"
            + "fetch('/rm?f='+encodeURIComponent(p),{method:'POST'}).then(function(r){"
            + "if(r.status===409){e.textContent='Klasör boş değil'}"
            + "else if(r.status===403){e.textContent='Bu klasöre yazılamıyor'}"
            + "else if(r.status===401){location.reload();return}"
            + "sig='';refresh()})}"

            + "drop.onclick=function(){fi.click()};"
            + "fi.onchange=function(){send(fi.files)};"
            + "drop.ondragover=function(ev){ev.preventDefault();drop.className='hot'};"
            + "drop.ondragleave=function(){drop.className=''};"
            + "drop.ondrop=function(ev){ev.preventDefault();drop.className='';"
            + "send(ev.dataTransfer.files)};"

            + "function send(files){if(!files||!files.length)return;"
            + "if(!writable){e.textContent='Önce yazılabilir bir klasör seç';return}"
            + "e.textContent='';"
            + "var fd=new FormData();for(var i=0;i<files.length;i++)fd.append('f',files[i]);"
            + "var x=new XMLHttpRequest();"
            + "x.open('POST','/up?p='+encodeURIComponent(cur));"
            + "x.upload.onprogress=function(ev){if(ev.lengthComputable)"
            + "pb.style.width=(ev.loaded/ev.total*100)+'%'};"
            + "x.onload=function(){pb.style.width='0';fi.value='';"
            + "if(x.status===507){e.textContent='Kutuda yeterli yer yok'}"
            + "else if(x.status===403){e.textContent='Bu klasöre yazılamıyor'}"
            + "else if(x.status===401){location.reload()}"
            + "else if(x.status!==200){e.textContent='Yükleme başarısız'}"
            + "sig='';refresh()};"
            + "x.onerror=function(){pb.style.width='0';e.textContent='Bağlantı koptu'};"
            + "x.send(fd)}"

            + "try{cur=decodeURIComponent((location.hash||'').substring(1))}catch(x){cur=''}"
            + "refresh();setInterval(refresh,5000);"
            + "</script></div></body></html>";
}
