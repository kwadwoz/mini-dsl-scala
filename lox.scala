import TokenType.*

// Central error reporting + entry point.
object Lox:
  var hadError = false

  def error(line: Int, message: String): Unit =
    report(line, "", message)

  def error(token: Token, message: String): Unit =
    if token.tokenType == EOF then report(token.line, " at end", message)
    else report(token.line, s" at '${token.lexeme}'", message)

  private def report(line: Int, where: String, message: String): Unit =
    System.err.println(s"[line $line] Error$where: $message")
    hadError = true

  def run(source: String): Unit =
    val tokens = Scanner(source).scanTokens()
    val statements = Parser(tokens).parse()
    if hadError then return
    // Pretty-print the parsed AST so we can see step 1+2.
    println("=== AST ===")
    statements.foreach(s => println(AstPrinter.print(s)))
    // Step 3: generate Verilog + Python client for the FPGA (FSM backend).
    val gen = FsmCodegen.generate(statements)
    java.nio.file.Files.writeString(java.nio.file.Path.of("design.v"), gen.verilog)
    java.nio.file.Files.writeString(java.nio.file.Path.of("client_sdk.py"), gen.python)
    println("\n(wrote design.v and client_sdk.py)")

    // Equivalence check: interpreter (golden model) vs simulated FPGA design.
    println("\n=== interpreter vs FPGA equivalence check ===")
    val ok = Verify.run(statements, gen)
    println(if ok then "\nALL MATCH — the hardware matches the interpreter."
            else "\nMISMATCH — see table above.")

// A tiny AST pretty-printer so we can eyeball parser output.
object AstPrinter:
  def print(stmt: Stmt): String = stmt match
    case Stmt.VarDecl(name, init) =>
      s"(var ${name.lexeme}${init.map(e => " = " + print(e)).getOrElse("")})"
    case Stmt.ExprStmt(e)   => print(e)
    case Stmt.Print(e)      => s"(print ${print(e)})"
    case Stmt.Return(_, v)  => s"(return${v.map(e => " " + print(e)).getOrElse("")})"
    case Stmt.Block(stmts)  => stmts.map(print).mkString("(block ", " ", ")")
    case Stmt.If(c, t, e) =>
      s"(if ${print(c)} ${print(t)}${e.map(s => " " + print(s)).getOrElse("")})"
    case Stmt.While(c, b)   => s"(while ${print(c)} ${print(b)})"

  def print(expr: Expr): String = expr match
    case Expr.Literal(v)          => if v == null then "nil" else v.toString
    case Expr.Variable(name)      => name.lexeme
    case Expr.Unary(op, r)        => s"(${op.lexeme} ${print(r)})"
    case Expr.Binary(l, op, r)    => s"(${op.lexeme} ${print(l)} ${print(r)})"
    case Expr.Logical(l, op, r)   => s"(${op.lexeme} ${print(l)} ${print(r)})"
    case Expr.Grouping(e)         => s"(group ${print(e)})"
    case Expr.Assign(name, v)     => s"(= ${name.lexeme} ${print(v)})"

@main def main(): Unit =
  val source =
    """
      |var a;              // host input
      |var b;              // host input
      |var m;
      |if (a > b) {        // conditional -> FSM
      |  m = a;
      |} else {
      |  m = b;
      |}
      |return m;           // max(a, b)
      |""".stripMargin
  Lox.run(source)
