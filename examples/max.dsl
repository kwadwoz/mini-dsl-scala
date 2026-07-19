// max(a, b) — conditional lowered to an FSM
var a;              // host input
var b;              // host input
var m = 0;          // scratch local (initialized -> not a host input)
if (a > b) {
  m = a;
} else {
  m = b;
}
return m;
