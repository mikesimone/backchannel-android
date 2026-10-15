import os
CSS = """
*{box-sizing:border-box;margin:0;padding:0}
body{width:1080px;height:1920px;background:#1F7A6D;font-family:Inter,'Noto Color Emoji',sans-serif;overflow:hidden}
.cap{height:330px;padding:70px 80px 0;color:#fff}
.cap h1{font-size:68px;font-weight:700;line-height:1.1;letter-spacing:-1px}
.cap p{font-size:36px;color:#DFF5F1;margin-top:18px}
.phone{position:absolute;left:70px;top:350px;width:940px;height:1530px;background:#fff;border-radius:56px;overflow:hidden;border:14px solid #10302B}
.status{height:60px;display:flex;justify-content:space-between;padding:16px 40px;font-size:26px;color:#111;font-weight:600}
.bar{height:120px;display:flex;align-items:center;padding:0 28px;gap:22px;border-bottom:1px solid #eee}
.bar .t{font-size:38px;font-weight:600;color:#111}
.bar .s{font-size:24px;color:#666}
.av{width:76px;height:76px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:#fff;font-size:30px;font-weight:600;flex:none}
.back{font-size:44px;color:#333}
.msgs{padding:22px 26px;display:flex;flex-direction:column;gap:16px}
.row{display:flex;gap:14px;align-items:flex-end}
.row.out{justify-content:flex-end}
.b{max-width:640px;padding:20px 26px;border-radius:30px;font-size:31px;line-height:1.35;color:#111;background:#ECECEF}
.out .b{background:#2C6BED;color:#fff}
.who{font-size:24px;font-weight:600;margin-bottom:4px}
.meta{font-size:21px;opacity:.6;text-align:right;margin-top:6px}
.sav{width:56px;height:56px;font-size:24px}
.q{border-left:6px solid #2C6BED;background:rgba(0,0,0,.06);border-radius:14px;padding:12px 16px;margin-bottom:10px;font-size:26px}
.out .q{border-left-color:#fff;background:rgba(255,255,255,.2)}
.q b{display:block;font-size:23px}
.compose{position:absolute;left:0;right:0;bottom:0;height:140px;border-top:1px solid #eee;display:flex;align-items:center;gap:20px;padding:0 28px;background:#fff}
.field{flex:1;background:#F0F0F2;border-radius:40px;padding:22px 30px;font-size:31px;color:#111}
.send{width:84px;height:84px;border-radius:50%;background:#2C6BED;color:#fff;display:flex;align-items:center;justify-content:center;font-size:38px}
.dim{position:absolute;inset:0;background:rgba(0,0,0,.45)}
.menu{position:absolute;background:#fff;border-radius:28px;box-shadow:0 10px 40px rgba(0,0,0,.3);padding:12px 0;font-size:32px;width:520px}
.menu div{padding:24px 36px;display:flex;gap:24px;align-items:center}
.menu .hi{background:#E3F2EF;font-weight:600;color:#1F7A6D}
.sheet{position:absolute;left:0;right:0;bottom:0;background:#fff;border-radius:40px 40px 0 0;padding:28px 34px}
.sheet h2{font-size:36px;margin-bottom:16px}
.pick{display:flex;align-items:center;gap:22px;padding:16px 0;font-size:31px}
.chk{width:46px;height:46px;border-radius:50%;border:4px solid #bbb;margin-left:auto;display:flex;align-items:center;justify-content:center;color:#fff;font-size:28px}
.chk.on{background:#2C6BED;border-color:#2C6BED}
.mention{background:rgba(44,107,237,.18);border-radius:8px;padding:0 6px;color:#1a4fc0;font-weight:600}
.out .mention{background:rgba(255,255,255,.25);color:#fff}
.react{display:inline-block;background:#fff;border:2px solid #eee;border-radius:24px;padding:2px 12px;font-size:26px;margin-top:-10px}
.list .it{display:flex;gap:24px;align-items:center;padding:22px 30px}
.list .n{font-size:32px;font-weight:600;color:#111}
.list .l{font-size:27px;color:#666;margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:640px}
.list .tm{margin-left:auto;font-size:22px;color:#888;text-align:right}
.badge{background:#2C6BED;color:#fff;border-radius:20px;padding:2px 14px;font-size:22px;display:inline-block;margin-top:8px}
.poll .pq{font-size:32px;font-weight:600;margin-bottom:14px}
.opt{display:flex;justify-content:space-between;font-size:28px;margin:12px 0 4px}
.track{height:12px;border-radius:6px;background:rgba(0,0,0,.12)}
.track i{display:block;height:12px;border-radius:6px;background:#2C6BED}
mono{font-family:'DejaVu Sans Mono',monospace;font-size:28px;background:rgba(0,0,0,.08);padding:0 6px;border-radius:6px}
.out mono{background:rgba(255,255,255,.2)}
"""
C = {"D":"#D9534F","M":"#7B5BD6","P":"#E08A1E","J":"#2E9E6A","R":"#3B7DD8","S":"#C2477A","H":"#5A6B7B","K":"#16857A","B":"#8C6D3F"}
def av(i, cls="av", c=None): return f'<div class="{cls}" style="background:{c or C.get(i[0],"#777")}">{i}</div>'
def status(): return '<div class="status"><span>9:41</span><span>▂▄▆ 100%</span></div>'
def chatbar(name, sub, ini, c=None): return f'<div class="bar"><span class="back">←</span>{av(ini,c=c)}<div><div class="t">{name}</div><div class="s">{sub}</div></div></div>'
def inb(who, ini, body, meta="10:02"): return f'<div class="row">{av(ini,"av sav")}<div class="b"><div class="who" style="color:{C.get(ini[0])}">{who}</div>{body}<div class="meta">{meta}</div></div></div>'
def out(body, meta="10:04 ✓✓"): return f'<div class="row out"><div class="b">{body}<div class="meta">{meta}</div></div></div>'
def compose(t="Message"): return f'<div class="compose"><span style="font-size:44px;color:#666">＋</span><div class="field" style="color:{"#888" if t=="Message" else "#111"}">{t}</div><div class="send">➤</div></div>'
STICKER = '''<svg width="230" height="230" viewBox="0 0 100 100"><circle cx="50" cy="52" r="42" fill="#FFD23F" stroke="#3a2a00" stroke-width="3"/><ellipse cx="36" cy="44" rx="6" ry="9" fill="#3a2a00"/><ellipse cx="64" cy="44" rx="6" ry="9" fill="#3a2a00"/><path d="M30 62 Q50 82 70 62" fill="none" stroke="#3a2a00" stroke-width="5" stroke-linecap="round"/><path d="M78 14 l4 8 9 1 -7 6 2 9 -8-5 -8 5 2-9 -7-6 9-1z" fill="#ff6b6b"/></svg>'''

pages = {}
# 1 chat list
items = [("Saturday Hike crew","S","Priya: I'm bringing the good snacks","10:12",3),("Dana Whitfield","D","You: Sounds perfect, see you then","10:04",0),("Book Club (Nov)","B","Marcus: Chapter 9 was wild","9:51",12),("Mom","M","Sticker","9:30",0),("Jordan Lee","J","Thanks for the ride!","Tue",0),("Roommates","R","Rent's in, power bill is next","Mon",0),("Kai Nakamura","K","Poll: Thai or pizza?","Mon",0),("Hannah Ortiz","H","Voice message (0:42)","Sun",0)]
lst = "".join(f'<div class="it">{av(i)}<div><div class="n">{n}</div><div class="l">{l}</div></div><div class="tm">{t}<br>{f"<span class=badge>{u}</span>" if u else ""}</div></div>' for n,i,l,t,u in items)
pages["1-chats"] = ("Signal for people who actually have friends.","Same Signal network and encryption. A friendlier app.",
  status()+'<div class="bar"><div class="av" style="background:#1F7A6D;width:70px;height:70px">MS</div><div class="t">Backchannel</div><span style="margin-left:auto;font-size:40px;color:#444">⌕ ✎</span></div><div class="list">'+lst+'</div>')
# 2 reply privately
body = (status()+chatbar("Saturday Hike crew","14 members","SH","#C2477A")+'<div class="msgs">'
  + inb("Marcus","M","Trailhead at 8? Parking fills up fast")
  + inb("Priya","P","8 works. I'm bringing the good snacks 🍫")
  + inb("Dana","D","Is it okay if I bring my cousin? Kind of a long story","10:06")
  + '</div>'+compose()
  + '<div class="dim"></div>'
  + '<div style="position:absolute;left:26px;top:560px;right:26px"><div class="row">'+av("D","av sav")+'<div class="b" style="box-shadow:0 0 0 6px #fff"><div class="who" style="color:#D9534F">Dana</div>Is it okay if I bring my cousin? Kind of a long story<div class="meta">10:06</div></div></div></div>'
  + '<div class="menu" style="left:120px;top:800px"><div>↩ Reply</div><div class="hi">👤 Reply privately</div><div>➦ Forward</div><div>⧉ Copy</div><div>ⓘ Info</div></div>')
pages["2-reply-privately"] = ("Reply privately","Answer one person from a group, with their message quoted.", body)
# 3 sticker replies
body = (status()+chatbar("Mom","Online","M")+'<div class="msgs">'
  + '<div class="row">'+'<div class="b">Your dad finally fixed the garage door!! Only took him 3 years<div class="meta">9:28</div></div></div>'
  + '<div class="row out"><div style="text-align:right"><div class="b" style="display:inline-block;padding:14px 20px;margin-bottom:6px"><div class="q" style="margin:0"><b>Mom</b>Your dad finally fixed the garage door!!</div></div><br>'+STICKER+'<div style="font-size:21px;color:#888">9:30 ✓✓</div></div></div>'
  + '<div class="row"><div class="b">Ha! That is exactly his face right now<div class="meta">9:31</div></div></div>'
  + '</div>'+compose())
pages["3-sticker-replies"] = ("Sticker replies","Answer a message with a sticker, quote and all.", body)
# 4 markdown
body = (status()+chatbar("Dana Whitfield","Online","D")+'<div class="msgs">'
  + '<div class="row"><div class="b">Did you see the trail is closed past the ridge?<div class="meta">10:01</div></div></div>'
  + out('<b>Yes!</b> We take the <i>lower loop</i> instead. <s>Bring the map</s> Map is in the car.<br><mono>Trailhead lot B</mono>')
  + out('<b><i>Perfect.</i></b> See you at 8','10:05 ✓✓')
  + '</div>'
  + '<div style="position:absolute;left:40px;right:40px;bottom:170px;background:#10302B;color:#fff;border-radius:26px;padding:26px 30px;font-size:29px;line-height:1.5">Type <b>*bold*</b>, <i>_italic_</i>, <s>~strike~</s> or <mono style="color:#fff;background:rgba(255,255,255,.2)">`code`</mono><br>and it becomes real formatting.</div>'
  + compose('Running <b>5 minutes</b> late *sorry*'))
pages["4-markdown"] = ("Type it, it formats","Markdown shortcuts turn into Signal's own bold, italic and more.", body)
# 5 forward to many
chats = [("Saturday Hike crew","S",1),("Book Club (Nov)","B",1),("Roommates","R",1),("Jordan Lee","J",1),("Kai Nakamura","K",1),("Hannah Ortiz","H",1),("Mom","M",0)]
picks = "".join(f'<div class="pick">{av(i,"av sav")}<span>{n}</span><div class="chk {"on" if on else ""}">{"✓" if on else ""}</div></div>' for n,i,on in chats)
body = (status()+chatbar("Kai Nakamura","Online","K")+'<div class="msgs">'+inb("Kai","K","Free concert in the park Saturday, 7pm. Tell everyone!","9:44")+'</div><div class="dim"></div>'
  + '<div class="sheet" style="top:520px"><h2>Forward to 6 chats</h2>'+picks
  + '<div class="field" style="margin-top:22px">Who\'s in? <span class="mention">@Priya</span> <span class="mention">@Marcus</span></div>'
  + '<div style="display:flex;justify-content:flex-end;margin-top:24px"><div class="send">➤</div></div></div>')
pages["5-forward"] = ("Forward to every chat at once","No limit, add your own note, and @mention people.", body)
# 6 poll
pollb = ('<div class="poll"><div class="pq">Dinner Friday?</div>'
  + "".join(f'<div class="opt"><span>{o}</span><span>{v}</span></div><div class="track"><i style="width:{w}%"></i></div>' for o,v,w in [("Thai",5,62),("Pizza",2,25),("Tacos",1,13)])
  + '<div class="meta" style="text-align:left;margin-top:14px">Poll · 8 votes</div></div>')
body = (status()+chatbar("Book Club (Nov)","11 members","BC","#8C6D3F")+'<div class="msgs">'+inb("Kai","K",pollb,"Mon")+'</div><div class="dim"></div>'
  + '<div class="sheet" style="top:900px"><h2>Send this poll to 3 chats</h2>'
  + "".join(f'<div class="pick">{av(i,"av sav")}<span>{n}</span><div class="chk on">✓</div></div>' for n,i in [("Saturday Hike crew","S"),("Roommates","R"),("Jordan Lee","J")])
  + '<div style="font-size:26px;color:#666;margin-top:10px">Each chat gets its own poll, with its own votes.</div>'
  + '<div style="display:flex;justify-content:flex-end;margin-top:20px"><div class="send">➤</div></div></div>')
pages["6-polls"] = ("One poll, many chats","Ask everyone at once instead of rebuilding it in each group.", body)

for k,(h,p,b) in pages.items():
    open(f"{k}.html","w").write(f'<html><head><meta charset="utf-8"><style>{CSS}</style></head><body><div class="cap"><h1>{h}</h1><p>{p}</p></div><div class="phone">{b}</div></body></html>')
print(" ".join(pages))
