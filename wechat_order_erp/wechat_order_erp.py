import os, re, json, sqlite3, hashlib, threading, webbrowser
from pathlib import Path
from datetime import datetime
import tkinter as tk
from tkinter import ttk, messagebox
from fastapi import FastAPI
from fastapi.responses import HTMLResponse, StreamingResponse
from pydantic import BaseModel
import uvicorn, io
from openpyxl import Workbook

APP_DIR=Path(os.getenv("LOCALAPPDATA",str(Path.home()))) / "WeChatOrderERP"
APP_DIR.mkdir(parents=True,exist_ok=True)
DB_PATH=APP_DIR/"orders.db"
CONFIG_PATH=APP_DIR/"config.json"
PORT=8765

def db():
    c=sqlite3.connect(DB_PATH,check_same_thread=False); c.row_factory=sqlite3.Row; return c

def init_db():
    c=db()
    c.executescript("""
    CREATE TABLE IF NOT EXISTS messages(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      msg_hash TEXT UNIQUE NOT NULL,
      group_name TEXT NOT NULL,
      sender TEXT, content TEXT NOT NULL,
      msg_time TEXT NOT NULL, created_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS orders(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      message_id INTEGER NOT NULL, group_name TEXT NOT NULL, sender TEXT,
      recipient TEXT, phone TEXT, address TEXT, product TEXT, sku TEXT, spec TEXT,
      quantity INTEGER, confidence INTEGER DEFAULT 0, status TEXT DEFAULT '待确认',
      remark TEXT, raw_content TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
    """)
    c.commit(); c.close()

PHONE_RE=re.compile(r'(?<!\d)(1[3-9]\d{9})(?!\d)')
QTY_RE=re.compile(r'(?:数量|qty|x|×|\*)?\s*(\d+)\s*(?:件|个|套|盒|份|pcs?)',re.I)
NAME_RE=re.compile(r'(?:收件人|姓名|联系人)\s*[:：]?\s*([^\s,，;；]{2,10})')
SKU_RE=re.compile(r'(?:SKU|货号|款号|编码)\s*[:：]?\s*([A-Za-z0-9_\-]+)',re.I)
ADDR_HINTS=('省','市','区','县','镇','街','路','号','村','小区','栋','室','自治区')
ORDER_WORDS=('下单','要','来','订','购买','发货','寄','件','个','套','盒','份','收件','地址','手机号','电话','SKU','货号','款号')

def extract(text):
    text=(text or "").strip()
    phone=PHONE_RE.search(text); name=NAME_RE.search(text); sku=SKU_RE.search(text); qtys=QTY_RE.findall(text)
    lines=[x.strip() for x in re.split(r'[\r\n]+',text) if x.strip()]
    address=""
    for line in lines:
        if len(line)>=8 and sum(1 for h in ADDR_HINTS if h in line)>=2:
            address=line; break
    product=""
    for line in lines:
        if any(k in line for k in ('商品','品名','产品')):
            product=re.sub(r'^(商品|品名|产品)\s*[:：]?\s*','',line).strip(); break
    if not product:
        for line in lines:
            if len(line)<=60 and not PHONE_RE.search(line) and line!=address and any(w in line.lower() for w in ('黑','白','红','蓝','绿','粉','xl','xxl','款','码','色')):
                product=line; break
    d={"recipient":name.group(1) if name else "","phone":phone.group(1) if phone else "","address":address,"product":product,"sku":sku.group(1) if sku else "","spec":"","quantity":int(qtys[0]) if qtys else None}
    score=(25 if d["phone"] else 0)+(25 if d["address"] else 0)+(15 if d["quantity"] else 0)+(10 if d["sku"] else 0)+(10 if d["recipient"] else 0)+(15 if any(w in text for w in ORDER_WORDS) else 0)
    d["confidence"]=min(score,100); d["is_order"]=d["confidence"]>=40
    return d

def ingest(group_name,sender,content,msg_time=""):
    now=datetime.now().isoformat(timespec="seconds"); msg_time=msg_time or now
    h=hashlib.sha256(("%s|%s|%s|%s"%(group_name,sender,msg_time,content)).encode("utf-8")).hexdigest()
    c=db()
    try:
        cur=c.execute("INSERT INTO messages(msg_hash,group_name,sender,content,msg_time,created_at) VALUES(?,?,?,?,?,?)",(h,group_name,sender,content,msg_time,now))
    except sqlite3.IntegrityError:
        c.close(); return {"duplicate":True}
    d=extract(content); oid=None
    if d["is_order"]:
        status="待下单" if d["confidence"]>=90 else "待确认"
        cur=c.execute("""INSERT INTO orders(message_id,group_name,sender,recipient,phone,address,product,sku,spec,quantity,confidence,status,remark,raw_content,created_at,updated_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",(cur.lastrowid,group_name,sender,d["recipient"],d["phone"],d["address"],d["product"],d["sku"],d["spec"],d["quantity"],d["confidence"],status,"",content,now,now))
        oid=cur.lastrowid
    c.commit(); c.close(); return {"duplicate":False,"order_id":oid,"extract":d}

app=FastAPI()

class Msg(BaseModel):
    group_name:str
    sender:str=""
    content:str
    msg_time:str=""

@app.post("/api/message")
def api_message(m:Msg): return ingest(m.group_name,m.sender,m.content,m.msg_time)

@app.get("/api/orders")
def api_orders():
    c=db(); rows=[dict(r) for r in c.execute("SELECT * FROM orders ORDER BY id DESC LIMIT 500").fetchall()]; c.close(); return rows

@app.get("/api/export")
def api_export():
    c=db(); rows=c.execute("SELECT * FROM orders ORDER BY id DESC").fetchall(); c.close()
    wb=Workbook(); ws=wb.active; ws.title="订单"
    ws.append(["ID","群","发送人","收件人","电话","地址","商品","SKU","数量","置信度","状态","原始消息","创建时间"])
    for r in rows:
        ws.append([r["id"],r["group_name"],r["sender"],r["recipient"],r["phone"],r["address"],r["product"],r["sku"],r["quantity"],r["confidence"],r["status"],r["raw_content"],r["created_at"]])
    bio=io.BytesIO(); wb.save(bio); bio.seek(0)
    name="wechat_orders_"+datetime.now().strftime("%Y%m%d_%H%M%S")+".xlsx"
    return StreamingResponse(bio,media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",headers={"Content-Disposition":'attachment; filename="'+name+'"'})

HTML='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>微信群订单ERP</title><style>body{font-family:Arial,"Microsoft YaHei";background:#f5f6f8;margin:0;color:#222}.h{background:#111827;color:white;padding:18px 24px}.w{padding:18px}.cards{display:flex;gap:12px;flex-wrap:wrap}.c{background:white;padding:14px 18px;border-radius:10px;min-width:140px}.n{font-size:28px;font-weight:bold}button{padding:9px 13px;margin:12px 6px 12px 0}table{width:100%;border-collapse:collapse;background:white}th,td{padding:9px;border-bottom:1px solid #eee;text-align:left;vertical-align:top;font-size:13px}.raw{max-width:320px;white-space:pre-wrap}</style>
<div class=h><b>微信群订单 ERP V1</b></div><div class=w><div class=cards><div class=c><div class=n id=a>0</div>订单总数</div><div class=c><div class=n id=b>0</div>待确认</div><div class=c><div class=n id=c>0</div>待下单</div></div><button onclick=load()>刷新</button><button onclick="location.href='/api/export'">导出Excel</button><table><thead><tr><th>ID</th><th>状态</th><th>群/发送人</th><th>收件人</th><th>电话</th><th>地址</th><th>商品</th><th>数量</th><th>置信度</th><th>原始消息</th></tr></thead><tbody id=t></tbody></table></div>
<script>
function e(v){return String(v==null?'':v).replace(/[&<>"']/g,function(s){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[s]})}
async function load(){var d=await fetch('/api/orders').then(function(r){return r.json()});a.textContent=d.length;b.textContent=d.filter(function(x){return x.status==='待确认'}).length;c.textContent=d.filter(function(x){return x.status==='待下单'}).length;t.innerHTML=d.map(function(o){return '<tr><td>'+o.id+'</td><td>'+e(o.status)+'</td><td>'+e(o.group_name)+'<br>'+e(o.sender)+'</td><td>'+e(o.recipient)+'</td><td>'+e(o.phone)+'</td><td>'+e(o.address)+'</td><td>'+e(o.product)+'<br>'+e(o.sku)+'</td><td>'+e(o.quantity)+'</td><td>'+e(o.confidence)+'%</td><td class=raw>'+e(o.raw_content)+'</td></tr>'}).join('')}
load();setInterval(load,5000)
</script></html>'''

@app.get("/",response_class=HTMLResponse)
def home(): return HTML

class ERPWindow:
    def __init__(self):
        init_db()
        self.root=tk.Tk(); self.root.title("微信群订单 ERP V1"); self.root.geometry("650x520")
        self.running=False; self.server_started=False; self.status=tk.StringVar(value="准备就绪")
        ttk.Label(self.root,text="微信群订单 ERP V1",font=("Microsoft YaHei",18,"bold")).pack(pady=(18,6))
        ttk.Label(self.root,text="登录微信后填写群名即可监听",foreground="#666").pack()
        f=ttk.LabelFrame(self.root,text="监听微信群（每行一个群名）"); f.pack(fill="both",expand=True,padx=20,pady=15)
        self.groups=tk.Text(f,height=10,font=("Microsoft YaHei",11)); self.groups.pack(fill="both",expand=True,padx=10,pady=10)
        cfg=self.load_config(); self.groups.insert("1.0","\n".join(cfg.get("groups",[])))
        bar=ttk.Frame(self.root); bar.pack(pady=4)
        ttk.Button(bar,text="开始监听",command=self.start_listener).pack(side="left",padx=5)
        ttk.Button(bar,text="打开订单后台",command=lambda:webbrowser.open("http://127.0.0.1:%d"%PORT)).pack(side="left",padx=5)
        ttk.Button(bar,text="测试订单",command=self.test_order).pack(side="left",padx=5)
        ttk.Label(self.root,textvariable=self.status,font=("Microsoft YaHei",10)).pack(pady=8)
        self.log=tk.Text(self.root,height=8,state="disabled",font=("Consolas",9)); self.log.pack(fill="x",padx=20,pady=(0,15))
        self.start_server()
    def load_config(self):
        try:return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except:return {"groups":[]}
    def save_config(self,groups):
        CONFIG_PATH.write_text(json.dumps({"groups":groups},ensure_ascii=False,indent=2),encoding="utf-8")
    def addlog(self,s):
        def x():
            self.log.configure(state="normal"); self.log.insert("end",datetime.now().strftime("%H:%M:%S ")+s+"\n"); self.log.see("end"); self.log.configure(state="disabled")
        self.root.after(0,x)
    def start_server(self):
        if self.server_started:return
        self.server_started=True
        threading.Thread(target=lambda:uvicorn.run(app,host="127.0.0.1",port=PORT,log_level="warning"),daemon=True).start()
        self.addlog("ERP后台已启动")
    def test_order(self):
        r=ingest("测试订单群","测试客户","收件人：张三\n电话：13800138000\n浙江省杭州市西湖区文一路100号\n商品：黑色 XL 款\nSKU：TEST-001\n数量：2件")
        self.addlog("已写入测试订单" if r.get("order_id") else "测试消息已处理")
        webbrowser.open("http://127.0.0.1:%d"%PORT)
    def start_listener(self):
        if self.running:
            messagebox.showinfo("提示","监听已经在运行。"); return
        groups=[x.strip() for x in self.groups.get("1.0","end").splitlines() if x.strip()]
        if not groups:
            messagebox.showwarning("提示","请至少填写一个微信群名称。"); return
        self.save_config(groups); self.running=True; self.status.set("正在连接微信…")
        threading.Thread(target=self.listen_worker,args=(groups,),daemon=True).start()
    def listen_worker(self,groups):
        try:
            from wxauto4 import WeChat
            wx=WeChat(); self.addlog("微信连接成功")
            for group in groups:
                def cb(msg,chat,g=group):
                    try:
                        content=getattr(msg,"content","") or ""
                        sender=getattr(msg,"sender","") or getattr(msg,"sender_name","") or ""
                        if not isinstance(sender,str): sender=str(sender)
                        if content:
                            r=ingest(g,sender,content)
                            self.addlog(g+" 收到消息"+((" → 订单 #%s"%r.get("order_id")) if r.get("order_id") else ""))
                    except Exception as ex:
                        self.addlog("处理消息异常："+str(ex))
                wx.AddListenChat(group,cb); self.addlog("已监听："+group)
            self.root.after(0,lambda:self.status.set("监听中：%d 个群"%len(groups)))
            wx.KeepRunning()
        except Exception as e:
            self.running=False
            self.root.after(0,lambda:self.status.set("监听启动失败"))
            self.addlog("错误："+str(e))
            self.root.after(0,lambda:messagebox.showerror("微信监听启动失败",str(e)+"\n\n请确认：\n1. Windows 微信已登录\n2. 微信版本与 wxauto4 兼容\n3. 群名称填写正确"))
    def run(self): self.root.mainloop()

if __name__=="__main__":
    ERPWindow().run()
