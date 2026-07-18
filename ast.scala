// Expressions: things that produce a value.
enum Expr:
  case Literal(value: Any)                             // 123, "hi", true, nil
  case Variable(name: Token)                           // x
  case Unary(op: Token, right: Expr)                   // -x, !x
  case Binary(left: Expr, op: Token, right: Expr)      // a + b, a < b
  case Logical(left: Expr, op: Token, right: Expr)     // a and b, a or b
  case Grouping(expr: Expr)                            // ( expr )
  case Assign(name: Token, value: Expr)               // x = expr

// Statements: things that do something.
enum Stmt:
  case VarDecl(name: Token, initializer: Option[Expr]) // var x = expr;
  case ExprStmt(expr: Expr)                            // expr;
  case Print(expr: Expr)                               // print expr;
  case Return(keyword: Token, value: Option[Expr])     // return expr;
  case Block(statements: List[Stmt])                   // { ... }
  case If(cond: Expr, thenBranch: Stmt, elseBranch: Option[Stmt])
  case While(cond: Expr, body: Stmt)
