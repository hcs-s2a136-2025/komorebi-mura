# ホーム画面のアイコン（どんぐりの子・36×36 のドット絵）を作る。流したあと、apple-touch-icon.png・icon-192.png・icon-512.png・favicon-32.png（→ favicon.png）を
# src/main/resources/static/ に写し、game-src/template2.html の data: の2つも作りなおす（ゲームのページは1枚で動くので）
from PIL import Image
N=36
im=Image.new("RGB",(N,N))
px=im.load()
# 背景：上は木もれびの黄緑、下はやわらかいミント
top=(255,236,170); bot=(150,214,160)
for y in range(N):
    t=y/(N-1)
    c=tuple(int(top[i]*(1-t)+bot[i]*t) for i in range(3))
    for x in range(N): px[x,y]=c
# 上の葉っぱ（こもれび）
leaf=(92,170,96); leaf2=(66,140,78)
import math
for x in range(N):
    for y in range(0,9):
        h=4+int(2*math.sin(x*0.9))+(1 if x%5==0 else 0)
        if y<h: px[x,y]=leaf2 if (x+y)%3==0 else leaf
# 光の粒
for (x,y) in [(5,10),(30,8),(8,15),(28,14),(3,24),(32,22)]:
    for dx,dy in [(0,0),(1,0),(-1,0),(0,1),(0,-1)]:
        px[x+dx,y+dy]=(255,250,215) if (dx,dy)!=(0,0) else (255,255,240)
# どんぐり：帽子と体
cx,cy=18,22
cap=(140,92,54); capd=(110,70,40); capl=(176,124,78)
body=(214,150,86); bodyd=(184,118,62); bodyl=(236,184,120)
out=(70,44,28)
for y in range(N):
    for x in range(N):
        dx=(x-cx+0.5)/10.0; dy=(y-cy-1.5)/9.0
        if dx*dx+dy*dy<=1 and y>=cy-2:
            px[x,y]=bodyd if dy>0.6 or dx>0.72 else body
for y in range(N):
    for x in range(N):
        dx=(x-cx+0.5)/11.0; dy=(y-(cy-3)+0.5)/5.0
        if dx*dx+dy*dy<=1 and y<=cy-1:
            px[x,y]=capl if (x+y)%3==0 else (capd if (x-y)%4==0 else cap)
# 輪郭
src=im.copy(); sp=src.load()
def isA(c): return c in (cap,capd,capl,body,bodyd,bodyl)
for y in range(N):
    for x in range(N):
        if not isA(sp[x,y]):
            for dx,dy in [(1,0),(-1,0),(0,1),(0,-1)]:
                X,Y=x+dx,y+dy
                if 0<=X<N and 0<=Y<N and isA(sp[X,Y]): px[x,y]=out; break
# 帽子のじく＋ふたば
px[18,15]=out;px[18,14]=out;px[18,13]=(80,150,70)
g=(110,196,92); gd=(70,150,70)
for p in [(17,12),(16,12),(15,11),(14,11),(15,12),(16,11)]: px[p]=g
for p in [(19,12),(20,12),(21,11),(22,11),(20,11),(21,12)]: px[p]=g
px[14,12]=gd; px[22,12]=gd
# 体のつや
for p in [(11,21),(11,22),(12,21)]: px[p]=bodyl
# 目（黒目に白いひかり）
eye=(52,34,24)
for bx in (13,22):
    for p in [(bx,23),(bx+1,23),(bx,24),(bx+1,24),(bx,25),(bx+1,25)]: px[p]=eye
    px[bx,23]=(255,255,255)
# 口
px[17,27]=eye; px[18,28]=eye; px[19,27]=eye
# ほっぺ
for x,y in [(10,26),(11,26),(25,26),(26,26)]: px[x,y]=(244,128,128)
im.save("m.png")
for s,n in [(5,"apple-touch-icon.png"),(16,"x.png")]:
    im.resize((N*s,N*s),Image.NEAREST).save(n)
im.resize((180,180),Image.NEAREST).save("apple-touch-icon.png")
big=im.resize((504,504),Image.NEAREST); c=Image.new("RGB",(512,512),bot); c.paste(big,(4,4)); c.save("icon-512.png")
im.resize((192,192),Image.NEAREST).save("icon-192.png")
im.resize((72,72),Image.NEAREST).resize((32,32),Image.LANCZOS).save("favicon-32.png")
