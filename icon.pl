print "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\">\n";
my $cx = 500;
my $cy = 500;
my $i;
for (my $i = 2; $i < 9; $i++) {
	part($i*30, ($i+1)*30, 2**($i+2));
}
sub part {
	my ($irad,$orad,$nsteps) = @_;
	for (my $i = 0; $i < $nsteps; $i++) {
		if ($i & 1) {
			my $t1 = 2.0*3.1415927*$i/$nsteps;
			my $t2 = 2.0*3.1415927*($i+1.0)/$nsteps;
			my ($x1,$y1) = ($irad * cos($t1), $irad * sin($t1));
			my ($x2,$y2) = ($orad * cos($t1), $orad * sin($t1));
			my ($x3,$y3) = ($orad * cos($t2), $orad * sin($t2));
			my ($x4,$y4) = ($irad * cos($t2), $irad * sin($t2));
			$x1 += $cx; $x2 += $cx; $x3 += $cx; $x4 += $cx;
			$y1 += $cy; $y2 += $cy; $y3 += $cy; $y4 += $cy;
			print qq|<path d="M$x1,$y1 L$x2,$y2 L$x3,$y3 L$x4,$y4 Z" fill="#f00" stroke="none" />|;
		}
	}
}

print "</svg>\n";
