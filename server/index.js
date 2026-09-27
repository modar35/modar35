const http = require('http');
const fs = require('fs');
const crypto = require('crypto');
const path = require('path');
const PORT = Number(process.env.PORT || 8080);
const DB = process.env.DATA_FILE || path.join(__dirname, 'data.json');
let db = { users: [], orders: [], messages: [], otps: {} };
try { db = JSON.parse(fs.readFileSync(DB, 'utf8')); } catch (_) {}
const save = () => fs.writeFileSync(DB, JSON.stringify(db, null, 2));
const json = (res, code, body) => { res.writeHead(code, {'Content-Type':'application/json; charset=utf-8','Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'Content-Type, Authorization','Access-Control-Allow-Methods':'GET,POST,PATCH,OPTIONS'}); res.end(JSON.stringify(body)); };
const body = req => new Promise((resolve,reject)=>{ let s=''; req.on('data',x=>s+=x); req.on('end',()=>{try{resolve(s?JSON.parse(s):{})}catch(e){reject(e)}}); });
const token = user => Buffer.from(JSON.stringify({id:user.id,role:user.role,exp:Date.now()+604800000})).toString('base64url');
const auth = req => { try { const raw=(req.headers.authorization||'').replace('Bearer ',''); const x=JSON.parse(Buffer.from(raw,'base64url')); return x.exp>Date.now()?x:null; } catch (_) { return null; } };
const id = () => crypto.randomUUID();
function route(req,res){
  if(req.method==='OPTIONS') return json(res,204,{});
  const url = new URL(req.url, `http://${req.headers.host}`); const p=url.pathname;
  if(req.method==='GET' && p==='/api/health') return json(res,200,{ok:true,service:'gruzovichok-api',time:new Date().toISOString()});
  if(req.method==='POST' && p==='/api/auth/request-code') return body(req).then(x=>{ if(!/^\+?[0-9 ()-]{10,}$/.test(x.phone||'')) return json(res,400,{error:'Введите корректный телефон'}); const code=process.env.NODE_ENV==='production'?String(Math.floor(100000+Math.random()*900000)):'123456'; db.otps[x.phone]=code; save(); console.log(`[SMS] ${x.phone}: ${code}`); json(res,200,{ok:true,devCode:process.env.NODE_ENV==='production'?undefined:code}); });
  if(req.method==='POST' && p==='/api/auth/verify-code') return body(req).then(x=>{ if(db.otps[x.phone]!==x.code) return json(res,401,{error:'Неверный код'}); let u=db.users.find(a=>a.phone===x.phone); if(!u){u={id:id(),phone:x.phone,role:x.role==='driver'?'driver':'customer',name:'Новый пользователь',createdAt:new Date().toISOString()};db.users.push(u)} delete db.otps[x.phone]; save(); json(res,200,{token:token(u),user:u}); });
  const me=auth(req); if(!me) return json(res,401,{error:'Требуется авторизация'});
  if(req.method==='GET' && p==='/api/orders') return json(res,200,db.orders.filter(o=>!url.searchParams.get('status')||o.status===url.searchParams.get('status')));
  if(req.method==='POST' && p==='/api/orders') return body(req).then(x=>{ if(!x.from||!x.to) return json(res,400,{error:'Нужны адреса маршрута'}); const o={id:id(),customerId:me.id,...x,status:'open',createdAt:new Date().toISOString()};db.orders.unshift(o);save();json(res,201,o); });
  const om=p.match(/^\/api\/orders\/([^/]+)$/); if(om && req.method==='PATCH') return body(req).then(x=>{const o=db.orders.find(a=>a.id===om[1]);if(!o)return json(res,404,{error:'Заказ не найден'});if(x.status&&!['open','accepted','in_progress','completed','cancelled'].includes(x.status))return json(res,400,{error:'Неверный статус'});Object.assign(o,x);if(x.status==='accepted')o.driverId=me.id;save();json(res,200,o)});
  const mm=p.match(/^\/api\/orders\/([^/]+)\/messages$/); if(mm && req.method==='GET') return json(res,200,db.messages.filter(m=>m.orderId===mm[1]));
  if(mm && req.method==='POST') return body(req).then(x=>{if(!x.text)return json(res,400,{error:'Пустое сообщение'});const m={id:id(),orderId:mm[1],userId:me.id,text:x.text,createdAt:new Date().toISOString()};db.messages.push(m);save();json(res,201,m)});
  json(res,404,{error:'Маршрут не найден'});
}
http.createServer((req,res)=>Promise.resolve(route(req,res)).catch(e=>json(res,500,{error:e.message}))).listen(PORT,'0.0.0.0',()=>console.log(`Грузовичок API: http://0.0.0.0:${PORT}`));
