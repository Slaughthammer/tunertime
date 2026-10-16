#!/usr/bin/env python3
"""TunerTime Organ launcher icon: strobe rings in Crameri's batlow
colours (the same stops as batlow() in jni/interface.c) behind a front of
five organ pipes.

Writes the adaptive icon foreground (108dp) and the legacy round icon
(48dp) for every density into app/src/main/res/mipmap-*.  Needs Pillow:

    python tools/make_icon.py
"""
import math
import os
from PIL import Image, ImageDraw

BATLOW = [(1, 25, 89), (16, 63, 96), (28, 90, 98), (60, 109, 86),
	  (104, 123, 62), (157, 137, 43), (210, 147, 67), (248, 161, 123),
	  (253, 183, 188), (250, 204, 250)]
BG = BATLOW[0]		# deep blue, also the adaptive icon background
SS = 8			# supersampling


def batlow(t):
	t = min(max(t, 0.0), 1.0) * (len(BATLOW)-1)
	k = min(int(t), len(BATLOW)-2)
	f = t-k
	return tuple(int(round(a + (b-a)*f))
		     for a, b in zip(BATLOW[k], BATLOW[k+1]))


def ring(d, cx, cy, r1, r2, nseg, col, phase=0.0):
	"""every other segment of the annulus r1..r2 in col"""
	steps = 12
	for i in range(0, nseg, 2):
		t1 = 2*math.pi*i/nseg + phase
		t2 = 2*math.pi*(i+1)/nseg + phase
		pts = []
		for j in range(steps+1):
			t = t1 + (t2-t1)*j/steps
			pts.append((cx + r2*math.cos(t), cy + r2*math.sin(t)))
		for j in range(steps, -1, -1):
			t = t1 + (t2-t1)*j/steps
			pts.append((cx + r1*math.cos(t), cy + r1*math.sin(t)))
		d.polygon(pts, fill=col)


def pipe(d, x, top, bottom, w, body, shade, mouth):
	"""a front pipe: body with a shaded side, conical foot, and the
	mouth as a dark notch under the upper lip"""
	foot = bottom - w*1.5
	d.rectangle((x-w/2, top, x+w/2, foot), fill=body)
	d.rectangle((x+w/5, top, x+w/2, foot), fill=shade)
	d.polygon([(x-w/2, foot), (x+w/2, foot), (x+w/8, bottom),
		   (x-w/8, bottom)], fill=shade)
	my = foot - w*0.75
	d.polygon([(x-w/4, my), (x+w/4, my), (x+w/4, my+w*0.5),
		   (x-w/4, my+w*0.5)], fill=mouth)
	d.pieslice((x-w/4, my-w*0.3, x+w/4, my+w*0.3), 180, 360,
		   fill=mouth)


def draw(size, dp, legacy):
	"""dp: canvas size in dp (108 adaptive, 48 legacy)"""
	n = size*SS
	u = n/dp				# pixels per dp
	img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
	d = ImageDraw.Draw(img)
	cx = cy = n/2
	if legacy:
		d.ellipse((0, 0, n-1, n-1), fill=BG)
		scale = 44/66		# the 66dp safe zone, with a margin
	else:
		scale = 1.0
	s = u*scale
	# strobe rings around a dark centre, warm inside to cool outside,
	# segments doubling outwards like the original TunerTime icon
	rings = [(21, 25, 32), (25, 29, 64), (29, 33, 128)]
	for k, (r1, r2, nseg) in enumerate(rings):
		col = batlow(0.78 - 0.2*k)
		ring(d, cx, cy, r1*s, r2*s - 0.7*s, nseg, col)
	# pipe front: five pipes, tallest in the middle
	body, shade, mouth = batlow(1.0), batlow(0.86), BATLOW[0]
	bottom = cy + 15*s
	for dx, h in ((-11, 22), (-5.5, 28), (0, 34), (5.5, 28), (11, 22)):
		pipe(d, cx + dx*s, bottom - h*s, bottom, 3.8*s, body, shade,
		     mouth)
	return img.resize((size, size), Image.LANCZOS)


def main():
	res = os.path.join(os.path.dirname(os.path.abspath(__file__)),
			   "..", "app", "src", "main", "res")
	for name, mult in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2),
			   ("xxhdpi", 3), ("xxxhdpi", 4)):
		out = os.path.join(res, "mipmap-" + name)
		os.makedirs(out, exist_ok=True)
		draw(int(108*mult), 108, False).save(
			os.path.join(out, "ic_launcher_foreground.png"))
		draw(int(48*mult), 48, True).save(
			os.path.join(out, "ic_launcher.png"))


if __name__ == "__main__":
	main()
