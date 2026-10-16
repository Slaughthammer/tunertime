#!/bin/sh
# tools/measure/run.sh - builds the measurement drivers, runs them and
# draws the plots.  Run from the repository root:
#
#   sh tools/measure/run.sh            everything (about 30 min on 4 cores)
#   sh tools/measure/run.sh plot       only redraw from the CSVs in out/
#   sh tools/measure/run.sh build      only build
#
# Needs a C compiler (CC, default gcc) and Python 3 with numpy and
# matplotlib (PY, default python).  CSVs, plots and summary.txt go to
# tools/measure/out/; binaries and the what-if copies of jni/*.c
# (variants.py) to tools/measure/build/ (not committed).
set -e
CC=${CC:-gcc}
PY=${PY:-python}
D=tools/measure
B=$D/build
O=$D/out
mkdir -p "$B" "$O"
# (the MinGW define makes printf write NaN as "nan")
CF="-O2 -D__USE_MINGW_ANSI_STDIO=1"

build() {
	$PY $D/variants.py $B
	$CC $CF -Ijni -I$D -o $B/m_detect $D/m_detect.c jni/pitch.c -lm
	$CC $CF -Ijni -I$D -o $B/m_detect_hb $D/m_detect.c \
		$B/pitch_halfband.c -lm
	$CC $CF -Ijni -I$D -o $B/m_detect_fn $D/m_detect.c \
		$B/pitch_focusnear.c -lm
	$CC $CF -I$D/jni_stub -Ijni -I$D -o $B/m_drift $D/m_drift.c \
		jni/strobe.c -lm
	$CC $CF -I$D/jni_stub -Ijni -I$D -o $B/m_drift_onset $D/m_drift.c \
		$B/strobe_onset.c -lm
}

# prog mode [csv suffix]
run() {
	echo "$1 $2" >&2
	$B/$1 $2 > "$O/$(echo $1 | sed 's/^m_//; s/_.*//')_$2$3.csv"
}

what=${1:-all}
case $what in
plot) ;;
*) build ;;
esac
case $what in
all)
	run m_detect acc & run m_drift time & run m_drift_onset time _onset &
	run m_drift beat & wait
	run m_detect map & run m_detect_hb map _hb & run m_detect focus &
	run m_drift trem & wait
	run m_drift level & run m_detect decim & run m_detect_hb decim _hb &
	run m_detect_fn focus _near & run m_drift wind & wait
	# CPU last, alone
	run m_detect cpu; run m_drift cpu
	;;
build|plot) ;;
*) echo "unknown: $what" >&2; exit 1 ;;
esac
case $what in
all|plot) $PY $D/plot.py "$O" ;;
esac
