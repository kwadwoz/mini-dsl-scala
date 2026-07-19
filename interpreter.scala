import TokenType.*
import scala.collection.mutable

// Reference semantics for the DSL — the "golden model" the FPGA must match.
// All values are 32-bit unsigned, with wraparound arithmetic and unsigned
// comparisons, exactly like the generated Verilog (wire [31:0], no `signed`).
object Interpreter:
  private val Mask = 0xFFFFFFFFL
  private class ReturnEx(val value: Long) extends RuntimeException

  // Run `statements` with the given input variables; returns the 32-bit result.
  def run(statements: List[Stmt], inputs: Map[String, Long]): Long =
    val env = mutable.Map.empty[String, Long]
    inputs.foreach((k, v) => env(k) = v & Mask)
    try
      statements.foreach(exec(_, env))
      0L // no explicit return
    catch case r: ReturnEx => r.value

  private def exec(s: Stmt, env: mutable.Map[String, Long]): Unit = s match
    case Stmt.VarDecl(n, Some(init)) => env(n.lexeme) = eval(init, env)
    case Stmt.VarDecl(n, None)       => env.getOrElseUpdate(n.lexeme, 0L)
    case Stmt.ExprStmt(e)            => eval(e, env)
    case Stmt.Print(_)               => () // no output on hardware; ignore
    case Stmt.Return(_, v)           => throw ReturnEx(v.map(eval(_, env)).getOrElse(0L))
    case Stmt.Block(ss)              => ss.foreach(exec(_, env))
    case Stmt.If(c, t, e) =>
      if truthy(eval(c, env)) then exec(t, env) else e.foreach(exec(_, env))
    case Stmt.While(c, b) =>
      while truthy(eval(c, env)) do exec(b, env)

  private def truthy(v: Long): Boolean = (v & Mask) != 0L

  private def eval(e: Expr, env: mutable.Map[String, Long]): Long = e match
    case Expr.Literal(v)      => v match
      case d: Double  => d.toLong & Mask
      case b: Boolean => if b then 1L else 0L
      case null       => 0L
      case other      => sys.error(s"bad literal $other")
    case Expr.Variable(t)     => env.getOrElse(t.lexeme, 0L)
    case Expr.Grouping(inner) => eval(inner, env)
    case Expr.Assign(n, v)    => val r = eval(v, env); env(n.lexeme) = r; r
    case Expr.Unary(op, r) =>
      val x = eval(r, env)
      op.tokenType match
        case MINUS => (-x) & Mask
        case BANG  => if truthy(x) then 0L else 1L
        case _     => sys.error("bad unary")
    case Expr.Logical(l, op, r) =>
      val a = eval(l, env)
      op.tokenType match
        case OR  => if truthy(a) then 1L else if truthy(eval(r, env)) then 1L else 0L
        case AND => if !truthy(a) then 0L else if truthy(eval(r, env)) then 1L else 0L
        case _   => sys.error("bad logical")
    case Expr.Binary(l, op, r) =>
      val a = eval(l, env) & Mask
      val b = eval(r, env) & Mask
      op.tokenType match
        case PLUS          => (a + b) & Mask
        case MINUS         => (a - b) & Mask
        case STAR          => (a * b) & Mask
        case SLASH         => if b == 0 then Mask else (a / b) & Mask // Verilog x/0 -> all ones
        case GREATER       => if a > b then 1L else 0L
        case GREATER_EQUAL => if a >= b then 1L else 0L
        case LESS          => if a < b then 1L else 0L
        case LESS_EQUAL    => if a <= b then 1L else 0L
        case EQUAL_EQUAL   => if a == b then 1L else 0L
        case BANG_EQUAL    => if a != b then 1L else 0L
        case _             => sys.error("bad binary")
