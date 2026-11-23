"""Builds video.html: a timeline-driven feature tour of Backchannel for Android (animated mockups)."""
import base64, html, re, sys

SRC = "/home/user/backchannel-android/backchannel/screenshots/gen.py"
PHONE_CSS = re.search(r'CSS = """(.*?)"""', open(SRC).read(), re.S).group(1)
# Drop the screenshot-only page/body/caption/phone placement rules; keep the chat UI rules.
PHONE_CSS = "\n".join(l for l in PHONE_CSS.splitlines() if not l.startswith(("body{", ".cap", ".phone{", "*{")))
ICON = base64.b64encode(open(sys.argv[1] if len(sys.argv) > 1 else "icon.png", "rb").read()).decode()

C = {"D": "#D9534F", "M": "#7B5BD6", "P": "#E08A1E", "J": "#2E9E6A", "R": "#3B7DD8", "S": "#C2477A", "H": "#5A6B7B", "K": "#16857A", "B": "#8C6D3F", "A": "#B5532E"}


def av(i, cls="av", c=None):
    return f'<div class="{cls}" style="background:{c or C.get(i[0], "#777")}">{i}</div>'


def status():
    return '<div class="status"><span>9:41</span><span>▂▄▆ 100%</span></div>'


def chatbar(name, sub, ini, c=None):
    return f'<div class="bar"><span class="back">←</span>{av(ini, c=c)}<div><div class="t">{name}</div><div class="s">{sub}</div></div></div>'


def a(inner, t, fx="up", out=None, style="", cls=""):
    o = f' data-out="{out}"' if out is not None else ""
    return f'<div class="a {cls}" data-in="{t}" data-fx="{fx}"{o} style="{style}">{inner}</div>'


def inb(who, ini, body, meta="10:02", t=None):
    row = f'<div class="row">{av(ini, "av sav")}<div class="b"><div class="who" style="color:{C.get(ini[0])}">{who}</div>{body}<div class="meta">{meta}</div></div></div>'
    return a(row, t) if t is not None else row


def out(body, meta="10:04 ✓✓", t=None, extra=""):
    row = f'<div class="row out"><div class="b">{body}<div class="meta">{meta}</div></div>{extra}</div>'
    return a(row, t) if t is not None else row


def compose(text="Message", typ=None, clear=None):
    if typ:
        t0, t1, s = typ
        c = f' data-t2="{clear}"' if clear is not None else ""
        field = f'<div class="field"><span class="typ" data-t0="{t0}" data-t1="{t1}"{c} data-text="{html.escape(s)}"></span><span class="caret"></span></div>'
    else:
        field = f'<div class="field" style="color:#888">{text}</div>'
    return f'<div class="compose"><span style="font-size:44px;color:#666">＋</span>{field}<div class="send">➤</div></div>'


def tap(x, y, t):
    return f'<div class="tap" data-at="{t}" style="left:{x}px;top:{y}px"></div>'


def press(x, y, t):  # long-press: ring that holds
    return f'<div class="tap long" data-at="{t}" style="left:{x}px;top:{y}px"></div>'


def dim(t, out=None):
    return a("", t, "fade", out, cls="dimw", style="position:absolute;inset:0;background:rgba(0,0,0,.45)")


def menu(items, hi, x, y, t, out=None):
    rows = "".join(f'<div class="{"hi" if i == hi else ""}">{s}</div>' for i, s in enumerate(items))
    return a(f'<div class="menu" style="position:static">{rows}</div>', t, "pop", out, style=f"position:absolute;left:{x}px;top:{y}px")


def settings(title, rows):
    body = "".join(rows)
    return status() + f'<div class="bar"><span class="back">←</span><div class="t">{title}</div></div><div class="set">{body}</div>'


def srow(label, sub="", right="", t=None, on=None):
    sw = ""
    if on is not None:
        sw = f'<div class="sw{" on" if on is True else ""}" {f"data-on={on}" if isinstance(on, (int, float)) and on is not True else ""}><i></i></div>'
    inner = f'<div class="sr"><div><div class="sl">{label}</div>{f"<div class=ss>{sub}</div>" if sub else ""}</div><div class="srr">{right}{sw}</div></div>'
    return a(inner, t) if t is not None else inner


STICKER = '''<svg width="{w}" height="{w}" viewBox="0 0 100 100"><circle cx="50" cy="52" r="42" fill="#FFD23F" stroke="#3a2a00" stroke-width="3"/><ellipse cx="36" cy="44" rx="6" ry="9" fill="#3a2a00"/><ellipse cx="64" cy="44" rx="6" ry="9" fill="#3a2a00"/><path d="M30 62 Q50 82 70 62" fill="none" stroke="#3a2a00" stroke-width="5" stroke-linecap="round"/><path d="M78 14 l4 8 9 1 -7 6 2 9 -8-5 -8 5 2-9 -7-6 9-1z" fill="#ff6b6b"/></svg>'''
STICKER2 = '''<svg width="{w}" height="{w}" viewBox="0 0 100 100"><rect x="12" y="18" width="76" height="66" rx="18" fill="#8FD3FF" stroke="#0b3550" stroke-width="3"/><circle cx="38" cy="46" r="7" fill="#0b3550"/><circle cx="62" cy="46" r="7" fill="#0b3550"/><path d="M36 64 h28" stroke="#0b3550" stroke-width="5" stroke-linecap="round"/><path d="M50 18 v-10" stroke="#0b3550" stroke-width="4"/><circle cx="50" cy="7" r="5" fill="#ff6b6b"/></svg>'''
STICKER3 = '''<svg width="{w}" height="{w}" viewBox="0 0 100 100"><path d="M50 86 C10 60 8 30 30 22 C42 18 50 28 50 34 C50 28 58 18 70 22 C92 30 90 60 50 86Z" fill="#FF6B8B" stroke="#5a1023" stroke-width="3"/></svg>'''
STICKER4 = '''<svg width="{w}" height="{w}" viewBox="0 0 100 100"><circle cx="50" cy="50" r="40" fill="#B9F18C" stroke="#244b0c" stroke-width="3"/><path d="M30 42 q6-8 12 0 M58 42 q6-8 12 0" stroke="#244b0c" stroke-width="5" fill="none" stroke-linecap="round"/><ellipse cx="50" cy="66" rx="12" ry="9" fill="#244b0c"/></svg>'''


def stk(s, w=150):
    return s.replace("{w}", str(w))


def photo(c1, c2, w=420, h=300, label=""):
    return f'<div class="ph" style="width:{w}px;height:{h}px;background:linear-gradient(135deg,{c1},{c2})"><span>{label}</span></div>'


SCENES = []


def scene(n, title, sub, how, body, dur=9.0):
    SCENES.append(dict(n=n, title=title, sub=sub, how=how, body=body, dur=dur))


# ---------------------------------------------------------------- scenes
# 1 Markdown
scene("Markdown", "Type it, it formats",
      "Bold, italic, lists, headings, quotes and code. Everyone sees the formatting, Signal users too.",
      "Type * item, # Title, &gt; quote or ```",
      status() + chatbar("Dana Whitfield", "Online", "D") + '<div class="msgs">'
      + inb("Dana", "D", "What do we still need for Saturday?", "10:01", t=0.3)
      + out('<div style="font-size:36px;font-weight:700">Packing list</div>• Water<br>• Headlamps<br>• <b>The good snacks</b><div class="q" style="margin-top:10px">Trail closes at 6, so <i>no dawdling</i></div>', "10:03 ✓✓", t=5.6)
      + '</div>' + compose(typ=(1.2, 5.0, "# Packing list\n* Water\n* Headlamps\n* **The good snacks**\n> Trail closes at 6, so _no dawdling_"), clear=5.5)
      + tap(870, 1440, 5.2))

# 2 Emoticons + double-tap react
scene("Emoji", "Smileys turn into emoji. Double-tap to react.",
      "Typed :) ;) :( become emoji as you send. Double-tap any message to react with your favorite.",
      "Double-tap a message",
      status() + chatbar("Jordan Lee", "Online", "J") + '<div class="msgs">'
      + inb("Jordan", "J", "Got the tickets!! Front row", "9:12", t=0.3)
      + a(out('<span class="typ" data-t0="1.6" data-t1="2.6" data-text="No way :) ;)"></span>', "9:13 ✓✓"), 1.4, "fade", out=3.4)
      + a('<div class="row out" style="margin-top:-118px"><div class="b">No way 🙂 😉<div class="meta">9:13 ✓✓</div></div></div>', 3.4, "pop")
      + a('<div class="row"><div style="width:56px"></div><div class="react" style="margin-left:20px;font-size:34px">❤️ 1</div></div>', 6.4, "pop", style="margin-top:-30px")
      + '</div>' + compose() + tap(330, 330, 5.6) + tap(330, 330, 5.85))

# 3 Reply privately + group shown
scene("Reply privately", "Reply privately, and see the group",
      "Answer one person from a group in your 1:1 chat. The quote shows who said it and in which group.",
      "Long-press a group message &gt; Reply privately",
      status() + chatbar("Saturday Hike crew", "14 members", "SH", "#C2477A") + '<div class="msgs">'
      + inb("Marcus", "M", "Trailhead at 8? Parking fills up fast")
      + inb("Dana", "D", "Is it okay if I bring my cousin? Kind of a long story", "10:06")
      + '</div>' + compose()
      + press(420, 470, 0.8) + dim(1.4, 4.2)
      + menu(["↩ Reply", "👤 Reply privately", "★ Star", "➦ Forward", "⧉ Copy"], 1, 120, 620, 1.5, 4.2)
      + tap(330, 760, 3.4)
      + a('<div style="position:absolute;inset:0;background:#fff">' + status() + chatbar("Dana Whitfield", "Online", "D")
          + '<div class="msgs">'
          + a(out('<div class="q"><b>Dana · Saturday Hike crew</b>Is it okay if I bring my cousin?</div>Of course, the more the merrier', "10:07 ✓✓"), 6.8)
          + '</div>' + compose(typ=(4.8, 6.4, "Of course, the more the merrier"), clear=6.8) + '</div>', 4.2, "slide"),
      dur=10)

# 4 Reply to one photo
scene("Photo replies", "Reply to one photo of an album",
      "Open a multi-photo message, swipe to the one you mean, and tap Reply. Add photos, GIFs, files or a sticker.",
      "Open the photo &gt; Reply at the top",
      status() + chatbar("Saturday Hike crew", "14 members", "SH", "#C2477A") + '<div class="msgs">'
      + inb("Priya", "P", '<div style="display:grid;grid-template-columns:1fr 1fr;gap:6px">' + photo("#7FB77E", "#2E5E3B", 230, 170, "") + photo("#F2B880", "#C46B3B", 230, 170, "") + photo("#8EC5FC", "#3B6BB5", 230, 170, "") + photo("#CDB4DB", "#7B5BD6", 230, 170, "+3") + '</div>', "9:58")
      + '</div>' + compose()
      + tap(560, 420, 0.8)
      + a('<div style="position:absolute;inset:0;background:#000">'
          + '<div style="display:flex;justify-content:space-between;color:#fff;font-size:30px;padding:80px 34px 0"><span>← Priya</span><span class="replybtn">↩ Reply</span></div>'
          + a(photo("#F2B880", "#C46B3B", 900, 640, "2 of 7"), 1.3, "fade", out=2.6, style="position:absolute;left:6px;top:420px")
          + a(photo("#8EC5FC", "#3B6BB5", 900, 640, "3 of 7"), 2.6, "slide", style="position:absolute;left:6px;top:420px")
          + '</div>', 1.2, "fade", out=4.6)
      + tap(800, 95, 3.8)
      + a('<div style="position:absolute;inset:0;background:#fff">' + status() + chatbar("Saturday Hike crew", "14 members", "SH", "#C2477A")
          + '<div class="msgs">' + a(out('<div class="q" style="display:flex;gap:14px;align-items:center"><div><b>Priya</b>Photo</div><div style="margin-left:auto">' + photo("#8EC5FC", "#3B6BB5", 90, 90) + '</div></div>That lake! Where is this?', "10:00 ✓✓"), 7.2) + '</div>'
          + compose(typ=(5.2, 6.8, "That lake! Where is this?"), clear=7.2) + '</div>', 4.6, "fade"),
      dur=10)

# 5 Stickers: full-size preview + favorites
grid = "".join(f'<div class="sk">{stk(s, 150)}</div>' for s in [STICKER, STICKER2, STICKER3, STICKER4, STICKER2, STICKER, STICKER4, STICKER3])
scene("Stickers", "Favorite stickers, seen full size",
      "Long-press a sticker to see it full size, send it, or add it to Favorites, the first tab in the picker.",
      "Sticker picker &gt; long-press a sticker",
      status() + chatbar("Mom", "Online", "M") + '<div class="msgs">'
      + '<div class="row"><div class="b">Your dad finally fixed the garage door!!<div class="meta">9:28</div></div></div></div>'
      + '<div class="kb"><div class="kbt"><span class="a" data-in="5.2" data-fx="pop">★</span><span>🕘</span><span class="on">😀</span><span>🐻</span></div><div class="kbg">' + grid + '</div></div>'
      + press(150, 1030, 0.9)
      + a('<div class="spv"><div style="font-size:54px">😀</div>' + stk(STICKER, 560) + '<div class="spb"><span>Send</span><span class="hi">★ Add to favorites</span><span>Close</span></div></div>', 1.5, "pop", out=4.6)
      + tap(470, 1290, 3.6)
      + a('<div class="toast">Added to Favorites</div>', 4.0, "up", out=5.6)
      + a('<div class="kb" style="z-index:3"><div class="kbt"><span class="on">★</span><span>🕘</span><span>😀</span><span>🐻</span></div><div class="kbg"><div class="sk">' + stk(STICKER, 150) + '</div></div></div>', 5.8, "fade")
      + tap(150, 1030, 7.0)
      + a('<div style="position:absolute;right:40px;top:560px;text-align:right">' + stk(STICKER, 220) + '<div style="font-size:21px;color:#888">9:30 ✓✓</div></div>', 7.4, "pop"),
      dur=10)

# 6 Forward to many + mentions
chats = [("Saturday Hike crew", "S"), ("Book Club (Nov)", "B"), ("Roommates", "R"), ("Jordan Lee", "J"), ("Kai Nakamura", "K"), ("Hannah Ortiz", "H")]
picks = "".join(f'<div class="pick">{av(i, "av sav")}<span>{n}</span><div class="chk" data-on="{1.6 + k * 0.45:.2f}">✓</div></div>' for k, (n, i) in enumerate(chats))
scene("Forward", "Forward to every chat at once",
      "No limit on how many chats. Add a note and @mention people in it.",
      "Long-press &gt; Forward &gt; pick chats",
      status() + chatbar("Kai Nakamura", "Online", "K") + '<div class="msgs">' + inb("Kai", "K", "Free concert in the park Saturday, 7pm. Tell everyone!", "9:44") + '</div>'
      + compose() + dim(0.8)
      + a('<div class="sheet" style="position:static"><h2>Forward to <span class="cnt" data-c0="1.6" data-step="0.45" data-max="6">0</span> chats</h2>' + picks
          + '<div class="field" style="margin-top:22px"><span class="typ" data-t0="4.6" data-t1="6.2" data-text="Who\'s in? "></span><span class="a mention" data-in="6.2" data-fx="pop">@Priya</span> <span class="a mention" data-in="6.6" data-fx="pop">@Marcus</span></div>'
          + '<div style="display:flex;justify-content:flex-end;margin-top:24px"><div class="send">➤</div></div></div>', 0.9, "slide", style="position:absolute;left:0;right:0;top:470px")
      + tap(830, 1390, 7.6)
      + a('<div class="toast">Sent to 6 chats</div>', 8.0, "up"))

# 7 Polls to many
pollb = ('<div class="poll"><div class="pq">Dinner Friday?</div>'
         + "".join(f'<div class="opt"><span>{o}</span><span>{v}</span></div><div class="track"><i class="grow" data-t0="0.4" style="width:{w}%"></i></div>' for o, v, w in [("Thai", 5, 62), ("Pizza", 2, 25), ("Tacos", 1, 13)])
         + '<div class="meta" style="text-align:left;margin-top:14px">Poll · 8 votes</div></div>')
scene("Polls", "One poll, many chats",
      "Send the same poll to several chats. Each chat gets its own poll and its own votes.",
      "New poll &gt; choose chats",
      status() + chatbar("Book Club (Nov)", "11 members", "BC", "#8C6D3F") + '<div class="msgs">' + inb("Kai", "K", pollb, "Mon") + '</div>' + compose()
      + dim(2.4)
      + a('<div class="sheet" style="position:static"><h2>Send this poll to 3 chats</h2>'
          + "".join(f'<div class="pick">{av(i, "av sav")}<span>{n}</span><div class="chk" data-on="{3.2 + k * 0.5:.1f}">✓</div></div>' for k, (n, i) in enumerate([("Saturday Hike crew", "S"), ("Roommates", "R"), ("Jordan Lee", "J")]))
          + '<div style="font-size:26px;color:#666;margin-top:10px">Each chat gets its own poll, with its own votes.</div>'
          + '<div style="display:flex;justify-content:flex-end;margin-top:20px"><div class="send">➤</div></div></div>', 2.5, "slide", style="position:absolute;left:0;right:0;top:880px")
      + tap(830, 1390, 6.4) + a('<div class="toast">Poll sent to 3 chats</div>', 6.8, "up"))

# 8 Starred messages
scene("Starred", "Star what matters. It stays.",
      "Starred messages are never deleted by Keep messages, chat length limits or disappearing timers, until you unstar them.",
      "Long-press &gt; Star",
      status() + chatbar("Roommates", "4 members", "R") + '<div class="msgs">'
      + inb("Sam", "S", "Wifi password is <mono>mango-tree-42</mono>", "Mon")
      + inb("Riley", "R", "Rent's in, power bill is next", "Mon")
      + a('<div style="position:absolute;left:150px;top:300px;font-size:34px">⭐</div>', 3.6, "pop")
      + '</div>' + compose()
      + press(420, 300, 0.7) + dim(1.3, 3.2) + menu(["↩ Reply", "★ Star", "⏰ Remind me", "➦ Forward"], 1, 140, 430, 1.4, 3.2) + tap(330, 540, 2.8)
      + a('<div style="position:absolute;inset:0;background:#fff">' + status() + '<div class="bar"><span class="back">←</span><div class="t">Starred messages</div></div><div class="msgs">'
          + inb("Sam · Roommates", "S", "Wifi password is <mono>mango-tree-42</mono>", "Mon")
          + inb("Dana · Dana Whitfield", "D", "Gate code is 4471#", "Oct 12")
          + a('<div class="note">🛡 Kept until you unstar it, whatever your auto-delete settings are</div>', 6.6, "up")
          + '</div></div>', 5.0, "slide"),
      dur=10)

# 9 Remind me
scene("Remind me", "Remind me about this",
      "Long-press any message and pick when. Backchannel nudges you with the message itself.",
      "Long-press &gt; Remind me",
      status() + chatbar("Hannah Ortiz", "Online", "H") + '<div class="msgs">' + inb("Hannah", "H", "Can you send me the photos from Saturday when you get a chance?", "Sun") + '</div>' + compose()
      + press(420, 300, 0.6) + dim(1.2, 4.6) + menu(["↩ Reply", "★ Star", "⏰ Remind me", "➦ Forward"], 2, 140, 430, 1.3, 2.6) + tap(330, 650, 2.3)
      + a('<div class="sheet" style="position:static"><h2>Remind me</h2>' + "".join(f'<div class="pick" style="{"color:#1F7A6D;font-weight:600" if k == 1 else ""}">{s}</div>' for k, s in enumerate(["In 1 hour", "This evening (6:00 PM)", "Tomorrow morning (9:00 AM)", "Pick a date and time"])) + '</div>', 2.6, "slide", out=4.6, style="position:absolute;left:0;right:0;bottom:0")
      + tap(330, 1250, 4.0)
      + a('<div class="notif"><b>⏰ Reminder · Hannah Ortiz</b><br>Can you send me the photos from Saturday when you get a chance?</div>', 5.8, "down"))

# 10 Translate
scene("Translate", "Translate, offline",
      "Long-press a message and tap Translate. It uses your phone's own offline translator; nothing leaves the phone.",
      "Long-press &gt; Translate",
      status() + chatbar("Lucía", "Online", "L", "#B5532E") + '<div class="msgs">' + inb("Lucía", "L", "¿Quedamos el sábado para cenar? Conozco un sitio genial cerca del puerto.", "8:20")
      + a('<div class="row"><div style="width:56px"></div><div class="b" style="background:#E3F2EF;margin-left:14px"><div class="who" style="color:#1F7A6D">Translated from Spanish · on this phone</div>Shall we meet on Saturday for dinner? I know a great place near the harbor.</div></div>', 3.8, "up")
      + '</div>' + compose() + press(420, 330, 0.6) + dim(1.2, 3.2) + menu(["↩ Reply", "🌐 Translate", "★ Star", "⧉ Copy"], 1, 140, 480, 1.3, 3.2) + tap(330, 600, 2.8))

# 11 Send silently
scene("Send silently", "Send silently",
      "Long-press Send. People on Backchannel get no sound or buzz. Other Signal apps notify as usual.",
      "Long-press Send &gt; Send silently",
      status() + chatbar("Jordan Lee", "Online", "J") + '<div class="msgs">' + inb("Jordan", "J", "Night shift this week, sleeping days 😴", "Mon")
      + out("Call me when you're up, no rush", "11:40 🔕 ✓✓", t=4.4) + '</div>'
      + compose(typ=(0.4, 1.8, "Call me when you're up, no rush"), clear=4.3) + press(870, 1440, 2.2)
      + a('<div class="menu" style="position:static;width:420px"><div class="hi">🔕 Send silently</div><div>🕘 Schedule message</div></div>', 2.7, "pop", out=4.2, style="position:absolute;right:30px;top:1220px")
      + tap(720, 1270, 3.8))

# 12 Blur media
scene("Blur", "Blur photos until you tap",
      "For every chat or just one. Your own sent photos stay clear.",
      "Settings &gt; Chats, or chat settings &gt; Blur photos and videos",
      status() + chatbar("Book Club (Nov)", "11 members", "BC", "#8C6D3F") + '<div class="msgs">'
      + inb("Marcus", "M", '<div class="blurwrap">' + photo("#F7C59F", "#E07A5F", 420, 300) + '<div class="blur a" data-in="0" data-out="2.6" data-fx="fade"><span>👁 Tap to view</span></div></div>', "9:51")
      + inb("Priya", "P", '<div class="blurwrap">' + photo("#A8DADC", "#457B9D", 420, 300) + '<div class="blur"><span>👁 Tap to view</span></div></div>', "9:53")
      + '</div>' + compose() + tap(330, 420, 2.2))

# 13 Immich
albums = [("Saturday hike", "37 photos · added today", "#7FB77E", "#2E5E3B"), ("Lake weekend", "112 photos · added 2 days ago", "#8EC5FC", "#3B6BB5"), ("Garage door saga", "9 photos · last week", "#F2B880", "#C46B3B")]
alist = "".join(a(f'<div class="pick">{photo(c1, c2, 110, 110)}<div><div style="font-size:31px">{n}</div><div style="font-size:24px;color:#777">{s}</div></div></div>', 2.6 + k * 0.2) for k, (n, s, c1, c2) in enumerate(albums))
scene("Immich", "Your own photo server, built in",
      "Attach straight from your Immich server. Search and sort your albums, sign in with your server's own login.",
      "Attach menu &gt; Immich",
      status() + chatbar("Saturday Hike crew", "14 members", "SH", "#C2477A") + '<div class="msgs">' + inb("Priya", "P", "Anyone get a shot of the summit?", "10:22") + '</div>' + compose()
      + tap(60, 1440, 0.5)
      + a('<div class="sheet" style="position:static"><div style="display:grid;grid-template-columns:repeat(4,1fr);gap:20px;text-align:center;font-size:26px"><div>🖼<br>Gallery</div><div>📷<br>Camera</div><div class="hiimm">☁<br>Immich</div><div>📄<br>File</div></div></div>', 0.9, "slide", out=2.0, style="position:absolute;left:0;right:0;bottom:0")
      + tap(600, 1400, 1.7)
      + a('<div style="position:absolute;inset:0;background:#fff">' + status() + '<div class="bar"><span class="back">←</span><div class="t">Immich albums</div><span style="margin-left:auto;font-size:28px;color:#1F7A6D">Sort: Recently added ▾</span></div>'
          + '<div class="field" style="margin:20px 28px"><span class="typ" data-t0="2.4" data-t1="3.4" data-text="sa"></span></div><div style="padding:0 28px">' + alist + '</div></div>', 2.0, "fade", out=6.2)
      + tap(300, 470, 5.6)
      + a('<div style="position:absolute;inset:0;background:#fff">' + status() + '<div class="bar"><span class="back">←</span><div class="t">Saturday hike</div></div><div style="display:grid;grid-template-columns:repeat(3,1fr);gap:8px;padding:8px">'
          + "".join(photo(c1, c2, 298, 298, "✓" if k == 1 else "") for k, (c1, c2) in enumerate([("#7FB77E", "#2E5E3B"), ("#A3C4BC", "#3E6259"), ("#F2E3BC", "#C19875"), ("#8EC5FC", "#3B6BB5"), ("#CDB4DB", "#7B5BD6"), ("#FFC8A2", "#D4A373")])) + '</div></div>', 6.2, "fade"),
      dur=10)

# 14 Lock a chat
scene("Chat lock", "Lock any chat",
      "Put a single chat behind your fingerprint. Everything else stays one tap away.",
      "Chat settings &gt; Lock this chat",
      settings("Dana Whitfield", [srow("Contact photo"), srow("Link to phone contact"), srow("Blur photos and videos", "", "Default"), srow("Save photos and videos to gallery", "", "Default"), srow("Lock this chat", "Ask for your fingerprint to open it", on=2.0)])
      + tap(830, 870, 1.6)
      + a('<div style="position:absolute;inset:0;background:#10302B;color:#fff;text-align:center;padding-top:520px"><div style="font-size:120px">🔒</div><div style="font-size:40px;margin-top:20px">Dana Whitfield is locked</div><div style="font-size:120px;margin-top:120px" class="fp">☝</div><div style="font-size:30px;opacity:.8">Touch the sensor</div></div>', 3.6, "fade", out=6.2)
      + a('<div style="position:absolute;inset:0;background:#fff">' + status() + chatbar("Dana Whitfield", "Online", "D") + '<div class="msgs">' + inb("Dana", "D", "Gate code is 4471#", "Oct 12") + '</div>' + compose() + '</div>', 6.2, "fade"))

# 15 Keyword alerts + auto reply
scene("Alerts & auto reply", "Keyword alerts and auto reply",
      "Muted chats still ping you for words you pick. An away message answers 1:1 chats, on a schedule if you like.",
      "Settings &gt; Notifications · Settings &gt; Chats &gt; Auto reply",
      settings("Keyword alerts", [srow("Keyword alerts", "Notify me in muted chats when someone says:", on=True),
                                  a('<div class="chips"><span>Mike</span><span>urgent</span><span class="a" data-in="1.6" data-fx="pop">dinner</span></div>', 0.4)])
      + a('<div class="notif"><b>🔔 Book Club (Nov) · muted</b><br>Priya: Who\'s up for <b>dinner</b> after?</div>', 2.6, "down", out=4.6)
      + a('<div style="position:absolute;inset:0;background:#fff">' + settings("Auto reply", [srow("Auto reply", "Answer 1:1 chats while you're away", on=True),
          srow("Message", "On vacation until Monday, back to you then!"), srow("Only between", "", "6:00 PM – 8:00 AM"), srow("Until", "", "Mon, Dec 1"),
          a('<div class="note">At most once every 4 hours per chat</div>', 6.2)]) + '</div>', 4.6, "slide"))

# 16 Contacts: hide/delete, contact photo, link to phone contact
scene("Contacts", "Your contacts, your way",
      "Hide or delete contacts without blocking. Give anyone your own photo, and link them to a phone contact even if they hide their number.",
      "New chat: long-press a contact · Chat settings",
      status() + '<div class="bar"><span class="back">←</span><div class="t">New chat</div></div><div class="list">'
      + "".join(f'<div class="it">{av(i)}<div><div class="n">{n}</div></div></div>' for n, i in [("Alex Rivera", "A"), ("Brandon (old work)", "B"), ("Dana Whitfield", "D"), ("Hannah Ortiz", "H")]) + '</div>'
      + press(400, 330, 0.6) + menu(["👁 Hide", "🗑 Delete contact", "🚫 Block"], 0, 220, 380, 1.2, 2.8) + tap(380, 430, 2.4)
      + a('<div class="toast">Brandon is hidden. Unhide in Settings &gt; Chats</div>', 2.9, "up", out=4.4)
      + a('<div style="position:absolute;inset:0;background:#fff">' + settings("Alex Rivera", [
          a('<div style="text-align:center;padding:30px 0"><div class="av" style="width:180px;height:180px;margin:0 auto;font-size:70px;background:linear-gradient(135deg,#F9A826,#B5532E)">🦊</div><div style="font-size:38px;font-weight:600;margin-top:16px">Alex Rivera</div><div style="font-size:26px;color:#666">Linked to phone contact “Alex R.”</div></div>', 5.6, "pop"),
          srow("Contact photo", "Your own photo, only on your devices", "Change"), srow("Link to phone contact", "", "Alex R. ✓")]) + '</div>', 4.4, "slide"),
      dur=10)

# 17 Gallery auto-save + hide group updates
scene("Gallery & groups", "Less clutter, more control",
      "Save received photos to your gallery, per chat or everywhere. Hide joined/left noise from people who aren't admins.",
      "Settings &gt; Chats",
      settings("Chats", [srow("Save photos and videos to gallery", "Never view-once or locked chats", on=1.2),
                         srow("Blur photos and videos", "", on=False),
                         srow("Hide group updates from regular members", "Joined, left, name and photo changes", on=2.6),
                         srow("Default emoji skin tone", "", "👍🏽"),
                         srow("Hidden contacts", "", "2")])
      + tap(840, 300, 0.9) + tap(840, 520, 2.3)
      + a('<div class="note" style="position:absolute;left:40px;right:40px;bottom:60px">Archived or muted groups are skipped unless you set them to Always</div>', 3.8, "up"))

# 18 Note to Self as a reminder
scene("Note to Self", "Note to Self stays unread",
      "A new note keeps Note to Self unread on every device until you open it again. A to-do pile that nags.",
      "Just write a note",
      status() + '<div class="bar"><div class="av" style="background:#1F7A6D;width:70px;height:70px">MS</div><div class="t">Backchannel</div></div><div class="list">'
      + '<div class="it">' + av("N", c="#2C6BED") + '<div><div class="n">Note to Self</div><div class="l"><span class="typ" data-t0="0.6" data-t1="2.0" data-text="Renew passport before March"></span></div></div><div class="tm">now<br><span class="badge a" data-in="2.2" data-fx="pop">1</span></div></div>'
      + "".join(f'<div class="it">{av(i)}<div><div class="n">{n}</div><div class="l">{l}</div></div><div class="tm">{t}</div></div>' for n, i, l, t in [("Saturday Hike crew", "S", "Priya: I'm bringing the good snacks", "10:12"), ("Dana Whitfield", "D", "You: Sounds perfect", "10:04"), ("Mom", "M", "Sticker", "9:30")])
      + '</div>' + a('<div class="note" style="position:absolute;left:40px;right:40px;bottom:80px">Stays unread on your phone and your linked computers</div>', 3.4, "up"), dur=8)

INTRO_DUR, OUTRO_DUR = 6.0, 7.0


def scene_html(i, s):
    feat = f'<div class="lab a" data-in="0.1" data-fx="fade">{i}/{len(SCENES)} · {s["n"]}</div>'
    left = (f'<div class="left">{feat}<h1 class="a" data-in="0.2" data-fx="up">{s["title"]}</h1>'
            f'<p class="a" data-in="0.45" data-fx="up">{s["sub"]}</p>'
            f'<div class="how a" data-in="0.7" data-fx="up"><span>How</span>{s["how"]}</div></div>')
    return f'<section class="scene" data-dur="{s["dur"]}">{left}<div class="pw"><div class="phone">{s["body"]}</div></div></section>'


intro = (f'<section class="scene full" data-dur="{INTRO_DUR}"><div class="center">'
         f'<img class="a" data-in="0.2" data-fx="pop" src="data:image/png;base64,{ICON}" style="width:220px;height:220px;border-radius:50px">'
         '<h1 class="a" data-in="0.6" data-fx="up" style="font-size:110px;margin-top:30px">Backchannel</h1>'
         '<p class="a" data-in="1.0" data-fx="up">Signal for people who actually have friends.</p>'
         '<p class="a small" data-in="1.6" data-fx="fade">A tour of what Backchannel adds · Android</p></div>'
         '<div class="disc a" data-in="1.6" data-fx="fade">Backchannel is not made by Signal and is not affiliated with Signal Messenger LLC or the Signal Foundation.</div></section>')
outro = (f'<section class="scene full" data-dur="{OUTRO_DUR}"><div class="center">'
         f'<img class="a" data-in="0.2" data-fx="pop" src="data:image/png;base64,{ICON}" style="width:180px;height:180px;border-radius:40px">'
         '<h1 class="a" data-in="0.5" data-fx="up" style="font-size:90px;margin-top:24px">Get Backchannel</h1>'
         '<p class="a" data-in="0.9" data-fx="up">Free on Google Play. No ads, no tracking.</p>'
         '<p class="a small" data-in="1.4" data-fx="fade">Same Signal network, same end-to-end encryption, friendlier app.</p></div>'
         '<div class="disc a" data-in="1.4" data-fx="fade">Screens shown are illustrations of the app. Names and conversations are made up.</div></section>')

CSS = PHONE_CSS + """
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:1920px;height:1080px;overflow:hidden;background:#0E3F38;font-family:Inter,'Noto Color Emoji',sans-serif}
.scene{position:absolute;inset:0;opacity:0;background:linear-gradient(120deg,#1F7A6D 0%,#15564D 100%)}
.left{position:absolute;left:110px;top:0;bottom:0;width:860px;display:flex;flex-direction:column;justify-content:center;color:#fff}
.lab{font-size:28px;letter-spacing:2px;text-transform:uppercase;color:#9FE3D6;font-weight:600}
.left h1{font-size:78px;line-height:1.05;font-weight:700;letter-spacing:-1.5px;margin-top:22px}
.left p{font-size:36px;line-height:1.35;color:#DFF5F1;margin-top:28px}
.how{margin-top:46px;font-size:30px;color:#fff;background:rgba(0,0,0,.22);border-radius:22px;padding:22px 28px;display:inline-block;align-self:flex-start}
.how span{display:inline-block;background:#9FE3D6;color:#0E3F38;font-weight:700;border-radius:12px;padding:2px 14px;margin-right:16px;font-size:24px;text-transform:uppercase;letter-spacing:1px}
.pw{position:absolute;right:170px;top:62px;width:583px;height:956px}
.phone{position:absolute;left:0;top:0;width:940px;height:1530px;background:#fff;border-radius:56px;overflow:hidden;border:14px solid #10302B;transform:scale(.62);transform-origin:0 0;box-shadow:0 40px 90px rgba(0,0,0,.35)}
.full .center{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;text-align:center}
.full p{font-size:44px;color:#DFF5F1;margin-top:20px}
.full p.small{font-size:30px;color:#9FE3D6}
.disc{position:absolute;left:0;right:0;bottom:46px;text-align:center;font-size:22px;color:rgba(255,255,255,.65)}
.a{will-change:opacity,transform}
.tap{position:absolute;width:90px;height:90px;margin:-45px 0 0 -45px;border-radius:50%;background:rgba(31,122,109,.35);border:4px solid rgba(31,122,109,.85);opacity:0;pointer-events:none;z-index:50}
.typ{white-space:pre-wrap}
.caret{display:inline-block;width:3px;height:34px;background:#2C6BED;vertical-align:middle;margin-left:2px}
.ph{border-radius:18px;display:flex;align-items:flex-end;justify-content:flex-end;color:#fff;font-size:34px;font-weight:700;padding:10px 16px;flex:none}
.kb{position:absolute;left:0;right:0;bottom:0;height:560px;background:#F3F3F5;border-top:1px solid #ddd}
.kbt{display:flex;gap:40px;padding:18px 34px;font-size:40px;border-bottom:1px solid #ddd}
.kbt span{opacity:.5}.kbt .on{opacity:1;border-bottom:5px solid #2C6BED}
.kbg{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;padding:20px}
.sk{display:flex;justify-content:center}
.spv{position:absolute;inset:0;background:rgba(10,10,10,.92);display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;gap:30px;z-index:5}
.spb{display:flex;gap:26px;font-size:30px}.spb span{background:rgba(255,255,255,.15);border-radius:30px;padding:18px 28px}.spb .hi{background:#1F7A6D}
.toast{position:absolute;left:50%;bottom:190px;transform:translateX(-50%);background:#222;color:#fff;font-size:28px;border-radius:30px;padding:18px 34px;white-space:nowrap;z-index:20}
.chk{transition:none}.chk.on{background:#2C6BED;border-color:#2C6BED}
.note{background:#E3F2EF;color:#10302B;border-radius:22px;padding:22px 26px;font-size:28px;margin-top:20px}
.notif{position:absolute;left:24px;right:24px;top:70px;background:#fff;border-radius:30px;box-shadow:0 14px 40px rgba(0,0,0,.25);padding:26px 30px;font-size:29px;line-height:1.35;z-index:30}
.blurwrap{position:relative;width:420px;height:300px}
.blur{position:absolute;inset:0;border-radius:18px;backdrop-filter:blur(26px);-webkit-backdrop-filter:blur(26px);background:rgba(255,255,255,.08);display:flex;align-items:center;justify-content:center}
.blur span{background:rgba(0,0,0,.55);color:#fff;font-size:28px;border-radius:30px;padding:12px 26px}
.set{padding:10px 0}
.sr{display:flex;justify-content:space-between;align-items:center;padding:30px 34px;border-bottom:1px solid #f0f0f0}
.sl{font-size:32px;color:#111}.ss{font-size:25px;color:#777;margin-top:6px;max-width:620px}
.srr{font-size:28px;color:#1F7A6D;display:flex;gap:16px;align-items:center}
.sw{width:96px;height:56px;border-radius:28px;background:#ccc;position:relative;flex:none}
.sw i{position:absolute;left:6px;top:6px;width:44px;height:44px;border-radius:50%;background:#fff;box-shadow:0 2px 6px rgba(0,0,0,.25)}
.sw.on{background:#1F7A6D}.sw.on i{left:46px}
.chips{display:flex;gap:16px;padding:20px 34px;flex-wrap:wrap}.chips span{background:#E3F2EF;color:#1F7A6D;border-radius:30px;padding:14px 26px;font-size:30px;font-weight:600}
.hiimm{color:#1F7A6D;font-weight:700}
.replybtn{background:rgba(255,255,255,.18);border-radius:24px;padding:6px 18px}
"""

JS = r"""
const scenes=[...document.querySelectorAll('.scene')];
document.querySelectorAll('.a').forEach(e=>{
  const c=e.firstElementChild;
  if(!e.style.position&&c&&getComputedStyle(c).position==='absolute'){e.style.position='absolute';e.style.inset='0';e.style.pointerEvents='none';}
});
const durs=scenes.map(s=>parseFloat(s.dataset.dur));
const starts=[];let acc=0;for(const d of durs){starts.push(acc);acc+=d;}
window.TOTAL=acc;const XF=0.45;
function ease(x){x=Math.max(0,Math.min(1,x));return 1-Math.pow(1-x,3);}
function applyScene(s,t){
  s.querySelectorAll('.a').forEach(e=>{
    const tin=parseFloat(e.dataset.in||0),tout=e.dataset.out!==undefined?parseFloat(e.dataset.out):1e9,fx=e.dataset.fx||'up';
    let p=ease((t-tin)/0.4), q=ease((t-tout)/0.3); let op=p*(1-q);
    let tr='';
    if(fx==='up')tr=`translateY(${(1-p)*30}px)`;
    else if(fx==='down')tr=`translateY(${(p-1)*60}px)`;
    else if(fx==='pop')tr=`scale(${0.85+0.15*p})`;
    else if(fx==='slide')tr=`translateY(${(1-p)*120}px)`;
    e.style.opacity=op;e.style.transform=tr;e.style.visibility=op<0.01?'hidden':'visible';
  });
  s.querySelectorAll('.typ').forEach(e=>{
    const t0=parseFloat(e.dataset.t0),t1=parseFloat(e.dataset.t1),txt=e.dataset.text;
    let n=Math.round(txt.length*Math.max(0,Math.min(1,(t-t0)/(t1-t0))));if(e.dataset.t2&&t>=parseFloat(e.dataset.t2))n=0;e.textContent=txt.slice(0,n);
  });
  s.querySelectorAll('.caret').forEach(e=>{e.style.opacity=(Math.floor(t*2)%2)?0:1;});
  s.querySelectorAll('.tap').forEach(e=>{
    const at=parseFloat(e.dataset.at),long=e.classList.contains('long'),len=long?0.9:0.45,x=(t-at)/len;
    if(x<0||x>1){e.style.opacity=0;return;}
    e.style.opacity=long?(x<0.8?1:(1-x)*5):(1-x);e.style.transform=`scale(${long?0.7+0.5*x:0.5+0.9*x})`;
  });
  s.querySelectorAll('[data-on]').forEach(e=>{const on=t>=parseFloat(e.dataset.on);e.classList.toggle('on',on);});
  s.querySelectorAll('.cnt').forEach(e=>{const c0=parseFloat(e.dataset.c0),st=parseFloat(e.dataset.step),mx=parseInt(e.dataset.max);e.textContent=Math.max(0,Math.min(mx,Math.floor((t-c0)/st)+1));});
  s.querySelectorAll('.grow').forEach(e=>{if(!e.dataset.w)e.dataset.w=e.style.width;const p=ease((t-parseFloat(e.dataset.t0))/0.8);e.style.width=(parseFloat(e.dataset.w)*p)+'%';});
}
window.render=function(T){
  scenes.forEach((s,i)=>{
    const t=T-starts[i],d=durs[i];
    let op=0;
    if(t>-XF&&t<d+XF){op=Math.min(1,(t+XF)/XF, (d+XF-t)/XF);if(i===0)op=Math.min(1,(d+XF-t)/XF);if(i===scenes.length-1)op=Math.min(1,(t+XF)/XF);}
    op=Math.max(0,Math.min(1,op));
    s.style.opacity=op;s.style.zIndex=op>0?1:0;s.style.display=op>0?'block':'none';
    if(op>0)applyScene(s,Math.max(0,t));
  });
};
window.chapters=function(){return starts.map((s,i)=>s);};
render(0);
"""

body = intro + "".join(scene_html(i + 1, s) for i, s in enumerate(SCENES)) + outro
open("video.html", "w").write(f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body>{body}<script>{JS}</script></body></html>')
# chapters for the YouTube description
t = 0.0
lines = []
for title, d in [("Intro", INTRO_DUR)] + [(s["title"], s["dur"]) for s in SCENES] + [("Get Backchannel", OUTRO_DUR)]:
    lines.append(f"{int(t // 60)}:{int(t % 60):02d} {title}")
    t += d
open("chapters.txt", "w").write("\n".join(lines) + "\n")
print(f"{len(SCENES)} scenes, {t:.0f}s")
