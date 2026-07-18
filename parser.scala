import TokenType.*
import scala.collection.mutable.ListBuffer

// A recursive-descent parser. Turns a flat list of tokens into an AST.
//
// Grammar (lowest to highest precedence):
//   program     -> statement* EOF
//   declaration -> varDecl | statement
//   varDecl     -> "var" IDENTIFIER ( "=" expression )? ";"
//   statement   -> exprStmt | printStmt | returnStmt | block | ifStmt | whileStmt
//   expression  -> assignment
//   assignment  -> IDENTIFIER "=" assignment | logic_or
//   logic_or    -> logic_and ( "or" logic_and )*
//   logic_and   -> equality ( "and" equality )*
//   equality    -> comparison ( ( "!=" | "==" ) comparison )*
//   comparison  -> term ( ( ">" | ">=" | "<" | "<=" ) term )*
//   term        -> factor ( ( "-" | "+" ) factor )*
//   factor      -> unary ( ( "/" | "*" ) unary )*
//   unary       -> ( "!" | "-" ) unary | primary
//   primary     -> NUMBER | STRING | "true" | "false" | "nil"
//                | IDENTIFIER | "(" expression ")"

class ParseError extends RuntimeException

class Parser(tokens: List[Token]):
  private var current = 0

  def parse(): List[Stmt] =
    val statements = ListBuffer.empty[Stmt]
    while !isAtEnd do
      declaration().foreach(statements += _)
    statements.toList

  // ---- statements ----

  private def declaration(): Option[Stmt] =
    try
      if matchType(VAR) then Some(varDeclaration())
      else Some(statement())
    catch
      case _: ParseError =>
        synchronize()
        None

  private def varDeclaration(): Stmt =
    val name = consume(IDENTIFIER, "Expect variable name.")
    val init = if matchType(EQUAL) then Some(expression()) else None
    consume(SEMICOLON, "Expect ';' after variable declaration.")
    Stmt.VarDecl(name, init)

  private def statement(): Stmt =
    if matchType(PRINT) then printStatement()
    else if matchType(RETURN) then returnStatement()
    else if matchType(LEFT_BRACE) then Stmt.Block(block())
    else if matchType(IF) then ifStatement()
    else if matchType(WHILE) then whileStatement()
    else expressionStatement()

  private def printStatement(): Stmt =
    val value = expression()
    consume(SEMICOLON, "Expect ';' after value.")
    Stmt.Print(value)

  private def returnStatement(): Stmt =
    val keyword = previous
    val value = if check(SEMICOLON) then None else Some(expression())
    consume(SEMICOLON, "Expect ';' after return value.")
    Stmt.Return(keyword, value)

  private def block(): List[Stmt] =
    val statements = ListBuffer.empty[Stmt]
    while !check(RIGHT_BRACE) && !isAtEnd do
      declaration().foreach(statements += _)
    consume(RIGHT_BRACE, "Expect '}' after block.")
    statements.toList

  private def ifStatement(): Stmt =
    consume(LEFT_PAREN, "Expect '(' after 'if'.")
    val cond = expression()
    consume(RIGHT_PAREN, "Expect ')' after if condition.")
    val thenBranch = statement()
    val elseBranch = if matchType(ELSE) then Some(statement()) else None
    Stmt.If(cond, thenBranch, elseBranch)

  private def whileStatement(): Stmt =
    consume(LEFT_PAREN, "Expect '(' after 'while'.")
    val cond = expression()
    consume(RIGHT_PAREN, "Expect ')' after condition.")
    val body = statement()
    Stmt.While(cond, body)

  private def expressionStatement(): Stmt =
    val expr = expression()
    consume(SEMICOLON, "Expect ';' after expression.")
    Stmt.ExprStmt(expr)

  // expressions 

  private def expression(): Expr = assignment()

  private def assignment(): Expr =
    val expr = or()
    if matchType(EQUAL) then
      val equals = previous
      val value = assignment()
      expr match
        case Expr.Variable(name) => Expr.Assign(name, value)
        case _ =>
          error(equals, "Invalid assignment target.")
          expr
    else expr

  private def or(): Expr =
    var expr = and()
    while matchType(OR) do
      val op = previous
      val right = and()
      expr = Expr.Logical(expr, op, right)
    expr

  private def and(): Expr =
    var expr = equality()
    while matchType(AND) do
      val op = previous
      val right = equality()
      expr = Expr.Logical(expr, op, right)
    expr

  private def equality(): Expr =
    var expr = comparison()
    while matchType(BANG_EQUAL, EQUAL_EQUAL) do
      val op = previous
      val right = comparison()
      expr = Expr.Binary(expr, op, right)
    expr

  private def comparison(): Expr =
    var expr = term()
    while matchType(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL) do
      val op = previous
      val right = term()
      expr = Expr.Binary(expr, op, right)
    expr

  private def term(): Expr =
    var expr = factor()
    while matchType(MINUS, PLUS) do
      val op = previous
      val right = factor()
      expr = Expr.Binary(expr, op, right)
    expr

  private def factor(): Expr =
    var expr = unary()
    while matchType(SLASH, STAR) do
      val op = previous
      val right = unary()
      expr = Expr.Binary(expr, op, right)
    expr

  private def unary(): Expr =
    if matchType(BANG, MINUS) then
      val op = previous
      val right = unary()
      Expr.Unary(op, right)
    else primary()

  private def primary(): Expr =
    if matchType(FALSE) then Expr.Literal(false)
    else if matchType(TRUE) then Expr.Literal(true)
    else if matchType(NIL) then Expr.Literal(null)
    else if matchType(NUMBER, STRING) then Expr.Literal(previous.literal)
    else if matchType(IDENTIFIER) then Expr.Variable(previous)
    else if matchType(LEFT_PAREN) then
      val expr = expression()
      consume(RIGHT_PAREN, "Expect ')' after expression.")
      Expr.Grouping(expr)
    else throw error(peek, "Expect expression.")

  // ---- token-stream helpers ----

  private def matchType(types: TokenType*): Boolean =
    if types.exists(check) then
      advance()
      true
    else false

  private def check(t: TokenType): Boolean =
    !isAtEnd && peek.tokenType == t

  private def advance(): Token =
    if !isAtEnd then current += 1
    previous

  private def isAtEnd: Boolean = peek.tokenType == EOF
  private def peek: Token = tokens(current)
  private def previous: Token = tokens(current - 1)

  private def consume(t: TokenType, message: String): Token =
    if check(t) then advance()
    else throw error(peek, message)

  private def error(token: Token, message: String): ParseError =
    Lox.error(token, message)
    new ParseError

  // After an error, skip tokens until we're likely at a new statement.
  private def synchronize(): Unit =
    advance()
    while !isAtEnd do
      if previous.tokenType == SEMICOLON then return
      peek.tokenType match
        case CLASS | FUN | VAR | FOR | IF | WHILE | PRINT | RETURN => return
        case _ => advance()