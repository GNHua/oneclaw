package com.tomandy.oneclaw.bridge.channel.webchat

object WebChatHtml {
    const val HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OneClaw WebChat</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#1a1a2e;color:#eee;font-family:system-ui,sans-serif;height:100vh;display:flex;flex-direction:column}
#header{background:#16213e;padding:12px 16px;font-size:18px;font-weight:600;border-bottom:1px solid #333}
#messages{flex:1;overflow-y:auto;padding:16px;display:flex;flex-direction:column;gap:8px}
.msg{max-width:80%;padding:10px 14px;border-radius:12px;line-height:1.5;white-space:pre-wrap;word-wrap:break-word}
.msg.user{background:#4a4a8a;align-self:flex-end;border-bottom-right-radius:4px}
.msg.assistant{background:#2a2a4a;align-self:flex-start;border-bottom-left-radius:4px}
.msg.system{background:#333;align-self:center;font-size:12px;color:#999}
#input-area{background:#16213e;padding:12px;display:flex;gap:8px;border-top:1px solid #333}
#input{flex:1;background:#0f3460;border:1px solid #444;border-radius:8px;padding:10px 14px;color:#eee;font-size:15px;outline:none}
#input:focus{border-color:#6a6aaa}
#send{background:#4a4a8a;border:none;border-radius:8px;padding:10px 20px;color:#eee;font-size:15px;cursor:pointer}
#send:hover{background:#5a5a9a}
#send:disabled{opacity:0.5;cursor:default}
#thinking{display:none;padding:4px 16px;color:#888;font-size:13px}
</style></head><body>
<div id="header">OneClaw</div>
<div id="messages"></div>
<div id="thinking">Thinking...</div>
<div id="input-area">
<input id="input" placeholder="Type a message..." autocomplete="off">
<button id="send">Send</button>
</div>
<script>
const params=new URLSearchParams(location.search);
const token=params.get('token')||'';
let ws,authenticated=false,reconnectMs=1000;
const msgs=document.getElementById('messages');
const input=document.getElementById('input');
const sendBtn=document.getElementById('send');
const thinking=document.getElementById('thinking');

function addMsg(text,cls){
  const d=document.createElement('div');
  d.className='msg '+cls;
  d.textContent=text;
  msgs.appendChild(d);
  msgs.scrollTop=msgs.scrollHeight;
}

function connect(){
  const proto=location.protocol==='https:'?'wss:':'ws:';
  ws=new WebSocket(proto+'//'+location.host+'/ws');
  ws.onopen=()=>{
    ws.send(JSON.stringify({type:'auth',token:token}));
    reconnectMs=1000;
  };
  ws.onmessage=(e)=>{
    const data=JSON.parse(e.data);
    if(data.type==='auth_ok'){authenticated=true;addMsg('Connected','system');}
    else if(data.type==='auth_fail'){addMsg('Auth failed. Add ?token=YOUR_TOKEN to URL','system');}
    else if(data.type==='typing'){thinking.style.display='block';}
    else if(data.type==='response'){thinking.style.display='none';sendBtn.disabled=false;addMsg(data.text,'assistant');}
    else if(data.type==='error'){thinking.style.display='none';sendBtn.disabled=false;addMsg('Error: '+data.text,'system');}
  };
  ws.onclose=()=>{authenticated=false;setTimeout(connect,reconnectMs);reconnectMs=Math.min(reconnectMs*2,30000);};
  ws.onerror=()=>{ws.close();};
}

function send(){
  const text=input.value.trim();
  if(!text||!authenticated)return;
  addMsg(text,'user');
  ws.send(JSON.stringify({type:'message',text:text}));
  input.value='';
  thinking.style.display='block';
  sendBtn.disabled=true;
}

sendBtn.onclick=send;
input.onkeydown=(e)=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send();}};
connect();
</script></body></html>"""
}
