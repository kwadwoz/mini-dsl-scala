// sum(0..n-1) — a while loop lowered to an FSM
var n;              // host input
var i = 0;
var sum = 0;
while (i < n) {
  sum = sum + i;
  i = i + 1;
}
return sum;
