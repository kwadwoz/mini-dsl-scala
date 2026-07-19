// gcd(a, b) via repeated subtraction (Euclid). Exercises while + if/else.
// Guarded so a 0 input terminates instead of looping forever.
var a;              // host input
var b;              // host input
while (a != b) {
  if (a == 0) {
    b = 0;          // degenerate: stop
  } else if (b == 0) {
    a = 0;          // degenerate: stop
  } else if (a > b) {
    a = a - b;
  } else {
    b = b - a;
  }
}
return a;           // gcd for positive inputs; 0 if either input is 0
