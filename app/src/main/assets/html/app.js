(() => {
'use strict';
const DB_NAME='LocalNotesDB', STORE='notes', DB_VERSION=1;
let db=null, storageMode='idb', notes=[], activeFilter='all', currentId=null, currentPinned=false, saveTimer=null, dialogResolve=null;
const $=id=>document.getElementById(id);
const els={list:$('noteList'),filters:$('filters'),search:$('searchInput'),clear:$('clearSearch'),count:$('countText'),editor:$('editor'),title:$('titleInput'),category:$('categoryInput'),body:$('bodyInput'),saveState:$('saveState'),pin:$('pinBtn'),wordCount:$('wordCount'),menu:$('menuOverlay'),dialog:$('dialogMask')};

function localRead(){try{return JSON.parse(localStorage.getItem('local_notes_v1')||'[]')}catch(e){return []}}
function localWrite(arr){localStorage.setItem('local_notes_v1',JSON.stringify(arr))}
function openDb(){return new Promise((resolve)=>{if(!window.indexedDB){storageMode='local';resolve(null);return;}try{const req=indexedDB.open(DB_NAME,DB_VERSION);req.onupgradeneeded=()=>{const d=req.result;if(!d.objectStoreNames.contains(STORE)){const st=d.createObjectStore(STORE,{keyPath:'id'});st.createIndex('updatedAt','updatedAt');}};req.onsuccess=()=>{db=req.result;storageMode='idb';resolve(db)};req.onerror=()=>{storageMode='local';resolve(null)};}catch(e){storageMode='local';resolve(null)}})}
function txStore(mode='readonly'){return db.transaction(STORE,mode).objectStore(STORE)}
function getAll(){if(storageMode==='local')return Promise.resolve(localRead());return new Promise((resolve,reject)=>{const r=txStore().getAll();r.onsuccess=()=>resolve(r.result||[]);r.onerror=()=>reject(r.error)})}
function putNote(n){if(storageMode==='local'){const arr=localRead();const i=arr.findIndex(x=>x.id===n.id);if(i>=0)arr[i]=n;else arr.push(n);localWrite(arr);return Promise.resolve(n);}return new Promise((resolve,reject)=>{const r=txStore('readwrite').put(n);r.onsuccess=()=>resolve(n);r.onerror=()=>reject(r.error)})}
function deleteNoteDb(id){if(storageMode==='local'){localWrite(localRead().filter(x=>x.id!==id));return Promise.resolve();}return new Promise((resolve,reject)=>{const r=txStore('readwrite').delete(id);r.onsuccess=()=>resolve();r.onerror=()=>reject(r.error)})}
function escapeHtml(s=''){return String(s).replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]))}
function normalize(s=''){return String(s).trim().toLowerCase()}
function now(){return Date.now()}
function uuid(){return 'n_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,9)}
function fmt(ts){const d=new Date(ts);const today=new Date();const same=d.toDateString()===today.toDateString();if(same)return d.toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false});return d.toLocaleDateString('zh-CN',{month:'2-digit',day:'2-digit'})+' '+d.toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false})}
function titleOf(n){return (n.title||'').trim() || firstLine(n.content) || '无标题笔记'}
function firstLine(s=''){return String(s).split(/\n/).map(x=>x.trim()).find(Boolean)||''}
function toast(msg){const el=$('toast');el.textContent=msg;el.classList.add('show');clearTimeout(el._t);el._t=setTimeout(()=>el.classList.remove('show'),1800)}
function haptic(){try{if(navigator.vibrate)navigator.vibrate(8)}catch(e){}}

async function refresh(){notes=(await getAll()).sort((a,b)=>(Number(b.pinned)-Number(a.pinned))||(b.updatedAt-a.updatedAt));renderFilters();renderList();els.count.textContent='本地保存 · '+notes.length+' 条';}
function categories(){const m=new Map();notes.forEach(n=>{const c=(n.category||'').trim();if(c)m.set(c,(m.get(c)||0)+1)});return [...m.entries()].sort((a,b)=>b[1]-a[1]).map(x=>x[0])}
function renderFilters(){const cats=categories();if(activeFilter!=='all'&&activeFilter!=='pinned'&&!cats.includes(activeFilter))activeFilter='all';const items=[['all','全部'],['pinned','置顶'],...cats.map(c=>[c,c])];els.filters.innerHTML=items.map(([v,label])=>`<button class="chip ${activeFilter===v?'active':''}" data-filter="${escapeHtml(v)}">${escapeHtml(label)}</button>`).join('');els.filters.querySelectorAll('.chip').forEach(b=>b.onclick=()=>{activeFilter=b.dataset.filter;renderFilters();renderList();});}
function renderList(){const q=normalize(els.search.value);els.clear.style.display=q?'block':'none';let arr=notes.filter(n=>{if(activeFilter==='pinned'&&!n.pinned)return false;if(activeFilter!=='all'&&activeFilter!=='pinned'&&(n.category||'')!==activeFilter)return false;if(!q)return true;return normalize((n.title||'')+' '+(n.content||'')+' '+(n.category||'')).includes(q)});if(!arr.length){els.list.innerHTML=`<div class="empty"><div class="emoji">${q?'⌕':'✎'}</div><strong>${q?'没有找到相关笔记':'还没有笔记'}</strong><span>${q?'换个关键词试试':'点右下角 + 开始第一条记录'}</span></div>`;return;}els.list.innerHTML=arr.map(n=>`<article class="card" data-id="${n.id}"><div class="card-top"><div class="card-title">${escapeHtml(titleOf(n))}</div>${n.pinned?'<span class="pin">★</span>':''}</div><div class="snippet">${escapeHtml((n.content||'').trim()||'暂无正文')}</div><div class="meta">${n.category?`<span class="tag">${escapeHtml(n.category)}</span>`:''}<span>${fmt(n.updatedAt)}</span></div></article>`).join('');els.list.querySelectorAll('.card').forEach(c=>c.onclick=()=>openExisting(c.dataset.id));}

function openNew(){currentId=uuid();currentPinned=false;els.title.value='';els.category.value='';els.body.value='';els.pin.textContent='☆';els.pin.classList.remove('on');els.editor.classList.add('show');els.saveState.textContent='开始输入后自动保存';updateWordCount();setTimeout(()=>els.title.focus(),80)}
function openExisting(id){const n=notes.find(x=>x.id===id);if(!n)return;currentId=n.id;currentPinned=!!n.pinned;els.title.value=n.title||'';els.category.value=n.category||'';els.body.value=n.content||'';els.pin.textContent=currentPinned?'★':'☆';els.pin.classList.toggle('on',currentPinned);els.editor.classList.add('show');els.saveState.textContent='已保存到本机';updateWordCount();}
function closeEditor(){clearTimeout(saveTimer);saveNow(true).then(()=>{els.editor.classList.remove('show');currentId=null;refresh();});}
function scheduleSave(){els.saveState.textContent='正在保存…';clearTimeout(saveTimer);saveTimer=setTimeout(()=>saveNow(false),450);updateWordCount();}
async function saveNow(silent){if(!currentId)return;const title=els.title.value, content=els.body.value, category=els.category.value.trim();const blank=!title.trim()&&!content.trim()&&!category;if(blank){els.saveState.textContent='开始输入后自动保存';return;}const old=notes.find(n=>n.id===currentId);const t=now();const n={id:currentId,title,content,category,pinned:currentPinned,createdAt:(old&&old.createdAt)||t,updatedAt:t};await putNote(n);const idx=notes.findIndex(x=>x.id===currentId);if(idx>=0)notes[idx]=n;else notes.push(n);els.saveState.textContent='已保存到本机';if(!silent)renderList();}
function updateWordCount(){const len=(els.title.value+els.body.value).replace(/\s/g,'').length;els.wordCount.textContent=len+' 字'}
async function deleteCurrent(){if(!currentId)return;const old=notes.find(n=>n.id===currentId);if(!old && !els.title.value.trim()&&!els.body.value.trim()){els.editor.classList.remove('show');currentId=null;return;}const ok=await confirmDialog('删除这条笔记？','删除后无法撤销。');if(!ok)return;await deleteNoteDb(currentId);els.editor.classList.remove('show');currentId=null;await refresh();toast('已删除');}
function confirmDialog(title,text){$('dialogTitle').textContent=title;$('dialogText').textContent=text;els.dialog.classList.add('show');return new Promise(resolve=>dialogResolve=resolve)}
function resolveDialog(v){els.dialog.classList.remove('show');if(dialogResolve){dialogResolve(v);dialogResolve=null}}

async function exportBackup(){await saveNow(true);await refresh();const payload={app:'随手笔记',version:1,exportedAt:new Date().toISOString(),notes};const text=JSON.stringify(payload,null,2);if(window.Android&&Android.exportBackup){Android.exportBackup(text)}else{toast('当前环境不支持系统文件导出')}}
function requestImport(){if(window.Android&&Android.importBackup)Android.importBackup();else toast('当前环境不支持导入')}
window.importBackupFromAndroid=async function(text){try{const data=JSON.parse(text);if(!data||!Array.isArray(data.notes))throw new Error('文件格式不正确');const ok=await confirmDialog('恢复备份？',`将导入 ${data.notes.length} 条笔记。相同 ID 的笔记会被备份内容覆盖。`);if(!ok)return;for(const raw of data.notes){if(!raw||!raw.id)continue;const n={id:String(raw.id),title:String(raw.title||''),content:String(raw.content||''),category:String(raw.category||''),pinned:!!raw.pinned,createdAt:Number(raw.createdAt)||now(),updatedAt:Number(raw.updatedAt)||now()};await putNote(n)}await refresh();closeMenu();toast('备份恢复完成');}catch(e){toast('恢复失败：'+(e.message||'文件无效'));}};
function openMenu(){els.menu.classList.add('show')} function closeMenu(){els.menu.classList.remove('show')}

$('addBtn').onclick=()=>{haptic();openNew()};$('backBtn').onclick=closeEditor;$('menuBtn').onclick=openMenu;els.menu.onclick=e=>{if(e.target===els.menu)closeMenu()};$('exportBtn').onclick=()=>{closeMenu();exportBackup()};$('importBtn').onclick=()=>{closeMenu();requestImport()};
$('deleteBtn').onclick=deleteCurrent;els.pin.onclick=()=>{currentPinned=!currentPinned;els.pin.textContent=currentPinned?'★':'☆';els.pin.classList.toggle('on',currentPinned);scheduleSave();};
[els.title,els.category,els.body].forEach(el=>el.addEventListener('input',scheduleSave));els.search.oninput=renderList;els.clear.onclick=()=>{els.search.value='';renderList();els.search.focus()};
$('dialogCancel').onclick=()=>resolveDialog(false);$('dialogOk').onclick=()=>resolveDialog(true);els.dialog.onclick=e=>{if(e.target===els.dialog)resolveDialog(false)};
document.addEventListener('visibilitychange',()=>{if(document.hidden&&currentId)saveNow(true)});
window.handleAndroidBack=function(){if(els.dialog.classList.contains('show')){resolveDialog(false);return 'handled'}if(els.menu.classList.contains('show')){closeMenu();return 'handled'}if(els.editor.classList.contains('show')){closeEditor();return 'handled'}return 'close'};

(async()=>{try{await openDb();await refresh();}catch(e){console.error(e);document.body.innerHTML='<div style="padding:30px;font-family:sans-serif">本地数据库初始化失败，请重新打开应用。</div>';}})();
})();
