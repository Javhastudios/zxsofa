from PIL import Image, ImageDraw, ImageFilter, ImageFont
import random
S=2                      # supermuestreo
W,H=1600,1100
w,h=W*S,H*S
img=Image.new('RGB',(w,h),(0,0,0))
d=ImageDraw.Draw(img)

def rr(box,r,fill,outline=None,width=0): d.rounded_rectangle([int(v*S) for v in box],int(r*S),fill=fill,outline=outline,width=int(width*S))

# Carcasa beige con degradado vertical
case=Image.new('RGB',(w,h))
cd=ImageDraw.Draw(case)
for y in range(h):
    t=y/h
    c=(int(226-22*t),int(208-24*t),int(166-26*t))
    cd.line([(0,y),(w,y)],fill=c)
mask=Image.new('L',(w,h),0); ImageDraw.Draw(mask).rounded_rectangle([0,0,w-1,h-1],60*S,fill=255)
img.paste(case,(0,0),mask)
# brillo superior y sombra inferior
d.rounded_rectangle([6*S,6*S,w-6*S,22*S],12*S,fill=(238,224,186))
d.rectangle([40*S,h-34*S,w-40*S,h-26*S],fill=(176,158,118))
# ligera textura de plástico
rnd=random.Random(1)
px=img.load()
for _ in range(60000):
    x=rnd.randrange(w); y=rnd.randrange(h)
    r,g,b=px[x,y]
    if r>120: k=rnd.randint(-6,6); px[x,y]=(r+k,g+k,b+k)

# Hueco del tubo (marco negro brillante)
HX,HY,HW,HH=70,70,1155,880   # caja del bisel
rr((HX-14,HY-14,HX+HW+14,HY+HH+14),120,(150,132,96))      # canto rebajado
rr((HX,HY,HX+HW,HY+HH),110,(24,22,22))                    # bisel negro
rr((HX+8,HY+8,HX+HW-8,HY+HH-8),104,(40,38,38))
# cristal del tubo (oscuro); la imagen del Spectrum se pinta encima
GX,GY,GW,GH=HX+44,HY+40,HW-88,HH-80
rr((GX,GY,GX+GW,GY+GH),70,(6,7,7))

# Panel derecho
PX=HX+HW+46; PW=W-PX-46
rr((PX,70,PX+PW,560),10,(212,194,152),outline=(170,150,110),width=3)
# franja de números
SX=PX+PW*0.5; d.rectangle([int(SX*S),int(92*S),int((SX+40)*S),int(540*S)],fill=(46,44,46))
try: font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',int(26*S)); small=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',int(22*S))
except: font=small=ImageFont.load_default()
for i in range(8):
    y=100+i*55
    d.line([int((PX+10)*S),int((y+48)*S),int((SX-6)*S),int((y+48)*S)],fill=(188,170,128),width=int(2*S))
    d.text((int((SX+12)*S),int((y+8)*S)),str(i+1),font=font,fill=(200,200,200))
    # tecla plateada
    bx=SX+52; be=PX+PW-14; rr((bx,y+14,be,y+34),3,(205,205,205),outline=(120,120,120),width=2)
    d.line([int((bx+2)*S),int((y+16)*S),int((be-2)*S),int((y+16)*S)],fill=(245,245,245),width=int(2*S))
# tres pilotos de color arriba
for i,c in enumerate([(60,140,220),(80,170,80),(210,70,70)]):
    cx=PX+PW*0.35+i*44; rr((cx,40,cx+34,56),8,c)
# rejilla del altavoz
for i in range(30):
    y=590+i*13
    d.line([int((PX)*S),int(y*S),int((PX+PW)*S),int(y*S)],fill=(170,150,112),width=int(4*S))
    d.line([int((PX)*S),int((y+4)*S),int((PX+PW)*S),int((y+4)*S)],fill=(232,216,176),width=int(2*S))
# botón de encendido
cx,cy=PX+PW-50,1010
d.ellipse([int((cx-24)*S),int((cy-24)*S),int((cx+24)*S),int((cy+24)*S)],fill=(30,30,30))
d.ellipse([int((cx-8)*S),int((cy-8)*S),int((cx+8)*S),int((cy+8)*S)],fill=(200,40,40))
# placa inferior izquierda
rr((HX+20,990,HX+260,1028),4,(30,28,30))
d.text((int((HX+36)*S),int(995*S)),"ZX SOFÁ",font=small,fill=(225,215,200))
d.text((int((HX+150)*S),int(999*S)),"color",font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',int(16*S)),fill=(200,90,90))

img=img.resize((W,H),Image.LANCZOS)
img.save('tv_frame.png',optimize=True)
print("hole", GX/W, GY/H, GW/W, GH/H, "aspect", GW/GH)
