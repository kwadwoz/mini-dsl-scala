import TokenType.*
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.LinkedHashSet

// Step 3b: FSM backend. Unlike the combinational VerilogCodegen, this supports
// sequential execution — assignments, `if`, and `while` — by compiling the
// program into a small instruction list and emitting a Verilog state machine.
//
// Calling convention:
//   CTRL   (0x00, write): bit0 = start pulse
//   STATUS (0x04, read):  bit0 = done, bit1 = busy
//   ARG[i] (0x10+4i, write): host inputs  -> DSL `var x;`
//   RESULT (0x40, read):  return value, valid once done=1
//   var debug regs (0x44+, read): every internal variable, for inspection
//
// The host writes args, pulses CTRL.start, polls STATUS.done, reads RESULT.

object FsmCodegen:
  // ---- tiny instruction set the FSM executes, one instruction per state ----
  private sealed trait Instr
  private case class SetReg(name: String, e: Expr)          extends Instr
  private case class BranchFalse(cond: Expr, var tgt: Int)  extends Instr // if !cond goto tgt
  private case class Goto(var tgt: Int)                     extends Instr
  private case class Finish(e: Option[Expr])               extends Instr // result<=e; done

  def generate(statements: List[Stmt], appName: String = "mini_dsl"): Generated =
    // 1. Inputs are uninitialized top-level vars; everything else assigned is internal.
    val inputs   = LinkedHashSet.empty[String]
    val internal = LinkedHashSet.empty[String]

    def scanTargets(s: Stmt): Unit = s match
      case Stmt.VarDecl(n, None)       => inputs += n.lexeme
      case Stmt.VarDecl(n, Some(_))    => internal += n.lexeme
      case Stmt.ExprStmt(Expr.Assign(n, _)) => internal += n.lexeme
      case Stmt.Block(ss)              => ss.foreach(scanTargets)
      case Stmt.If(_, t, e)            => scanTargets(t); e.foreach(scanTargets)
      case Stmt.While(_, b)            => scanTargets(b)
      case _                           => ()
    statements.foreach(scanTargets)
    // A name can't be both; inputs win only if never assigned.
    internal --= inputs

    // 2. Compile statements into a flat instruction list with patched jumps.
    val code = ListBuffer.empty[Instr]
    def here: Int = code.size

    def compile(s: Stmt): Unit = s match
      case Stmt.VarDecl(n, Some(init))        => code += SetReg(n.lexeme, init)
      case Stmt.VarDecl(_, None)              => () // input register, nothing to run
      case Stmt.ExprStmt(Expr.Assign(n, v))   => code += SetReg(n.lexeme, v)
      case Stmt.ExprStmt(_)                   => () // side-effect-free expr: skip
      case Stmt.Print(_)                      => () // no console on hardware
      case Stmt.Return(_, v)                  => code += Finish(v)
      case Stmt.Block(ss)                     => ss.foreach(compile)
      case Stmt.If(cond, thenB, elseB) =>
        val bf = BranchFalse(cond, -1); code += bf
        compile(thenB)
        elseB match
          case None => bf.tgt = here
          case Some(e) =>
            val skip = Goto(-1); code += skip
            bf.tgt = here
            compile(e)
            skip.tgt = here
      case Stmt.While(cond, body) =>
        val top = here
        val bf = BranchFalse(cond, -1); code += bf
        compile(body)
        code += Goto(top)
        bf.tgt = here
    statements.foreach(compile)
    code += Finish(None) // safety net if the program never returns

    // 3. Expression -> Verilog. Inputs use in_<name>, internals use <name>.
    def emit(e: Expr): String = e match
      case Expr.Literal(v)      => literal(v)
      case Expr.Variable(t)     => if inputs.contains(t.lexeme) then s"in_${t.lexeme}" else t.lexeme
      case Expr.Grouping(inner) => s"(${emit(inner)})"
      case Expr.Unary(op, r)    => if op.tokenType == MINUS then s"(-${emit(r)})" else s"(!${emit(r)})"
      case Expr.Binary(l, o, r) => s"(${emit(l)} ${binOp(o)} ${emit(r)})"
      case Expr.Logical(l, o, r)=> s"(${emit(l)} ${if o.tokenType == AND then "&&" else "||"} ${emit(r)})"
      case Expr.Assign(_, _)    => sys.error("nested assignment not supported")

    // 4. Register map (byte offsets, word address = offset >> 2).
    val CTRL = 0x00; val STATUS = 0x04; val RESULT = 0x40
    val inList  = inputs.toList
    val intList = internal.toList
    val argOff  = inList.zipWithIndex.map((n, i) => n -> (0x10 + i * 4)).toMap
    val dbgOff  = intList.zipWithIndex.map((n, j) => n -> (0x44 + j * 4)).toMap

    // 5. Emit Verilog.
    val v = StringBuilder()
    v ++= s"// Auto-generated FSM by mini-dsl-scala. App: $appName\n"
    v ++= "module top (\n"
    v ++= "  input  wire        clk, rst,\n"
    v ++= "  input  wire        wb_cyc, wb_stb, wb_we,\n"
    v ++= "  input  wire [8:0]  wb_adr,\n"
    v ++= "  input  wire [31:0] wb_dat_w,\n"
    v ++= "  input  wire [3:0]  wb_sel,\n"
    v ++= "  output reg  [31:0] wb_dat_r,\n"
    v ++= "  output reg         wb_ack\n"
    v ++= ");\n\n"

    inList.foreach(n => v ++= s"  reg [31:0] in_$n;\n")
    intList.foreach(n => v ++= s"  reg [31:0] $n;\n")
    v ++= "  reg [31:0] result;\n"
    v ++= "  reg        done, busy;\n"
    v ++= "  reg [15:0] state;\n"
    v ++= "  localparam S_IDLE = 16'd0;\n\n"

    v ++= "  wire wb_wr = wb_cyc & wb_stb &  wb_we & ~wb_ack;\n"
    v ++= "  wire wb_rd = wb_cyc & wb_stb & ~wb_we & ~wb_ack;\n"
    v ++= s"  wire start = wb_wr & (wb_adr == 9'd${CTRL >> 2}) & wb_dat_w[0];\n\n"

    // Wishbone block: ack, host writes to inputs, register reads.
    v ++= "  always @(posedge clk) begin\n"
    v ++= "    if (rst) begin\n"
    v ++= "      wb_ack <= 1'b0; wb_dat_r <= 32'h0;\n"
    inList.foreach(n => v ++= s"      in_$n <= 32'h0;\n")
    v ++= "    end else begin\n"
    v ++= "      wb_ack <= wb_cyc & wb_stb & ~wb_ack;\n"
    if inList.nonEmpty then
      v ++= "      if (wb_wr) begin\n        case (wb_adr)\n"
      inList.foreach(n => v ++= f"          9'd${argOff(n) >> 2}%-3s: in_$n <= wb_dat_w;\n")
      v ++= "          default: ;\n        endcase\n      end\n"
    v ++= "      if (wb_rd) begin\n        case (wb_adr)\n"
    v ++= f"          9'd${STATUS >> 2}%-3s: wb_dat_r <= {30'b0, busy, done};\n"
    v ++= f"          9'd${RESULT >> 2}%-3s: wb_dat_r <= result;\n"
    intList.foreach(n => v ++= f"          9'd${dbgOff(n) >> 2}%-3s: wb_dat_r <= $n;\n")
    v ++= "          default: wb_dat_r <= 32'h0;\n        endcase\n      end\n"
    v ++= "    end\n  end\n\n"

    // FSM block: one instruction per state.
    v ++= "  always @(posedge clk) begin\n"
    v ++= "    if (rst) begin\n"
    v ++= "      state <= S_IDLE; done <= 1'b0; busy <= 1'b0; result <= 32'h0;\n"
    intList.foreach(n => v ++= s"      $n <= 32'h0;\n")
    v ++= "    end else begin\n"
    v ++= "      case (state)\n"
    v ++= "        S_IDLE: if (start) begin done <= 1'b0; busy <= 1'b1; state <= 16'd1; end\n"
    code.zipWithIndex.foreach { (instr, i) =>
      val st = i + 1
      val next = i + 2
      val body = instr match
        case SetReg(n, e)        => s"$n <= ${emit(e)}; state <= 16'd$next;"
        case BranchFalse(c, tgt) => s"if (!(${emit(c)})) state <= 16'd${tgt + 1}; else state <= 16'd$next;"
        case Goto(tgt)           => s"state <= 16'd${tgt + 1};"
        case Finish(opt)         =>
          val r = opt.map(e => s"result <= ${emit(e)}; ").getOrElse("")
          s"${r}done <= 1'b1; busy <= 1'b0; state <= S_IDLE;"
      v ++= s"        16'd$st: begin $body end\n"
    }
    v ++= "        default: state <= S_IDLE;\n"
    v ++= "      endcase\n    end\n  end\n"
    v ++= "endmodule\n"

    // 6. Python client.
    val regList =
      RegInfo("ctrl", CTRL, "in") ::
      RegInfo("status", STATUS, "out") ::
      inList.map(n => RegInfo(n, argOff(n), "in")) :::
      RegInfo("result", RESULT, "out") ::
      intList.map(n => RegInfo(n, dbgOff(n), "out"))

    val p = StringBuilder()
    p ++= "import manhattan_reasoning_gym as mrg\n\n"
    p ++= "class Regs(mrg.cloud.RegisterMap):\n"
    regList.foreach(r => p ++= f"    ${r.name.toUpperCase}%-8s = 0x${r.byteOffset}%04X  # ${r.dir}\n")
    p ++= "\n"
    p ++= s"""app = mrg.cloud.App("$appName", design="design.v", registers=Regs)\n\n"""
    p ++= "@app.local_entrypoint()\n"
    p ++= "def main():\n"
    inList.zipWithIndex.foreach((n, i) =>
      p ++= f"    app.write(Regs.${n.toUpperCase}, ${i + 1})  # TODO: real input value\n")
    p ++= "    app.write(Regs.CTRL, 1)          # pulse start\n"
    p ++= "    while not (app.read(Regs.STATUS) & 1):  # wait for done\n"
    p ++= "        pass\n"
    p ++= s"""    print("result =", app.read(Regs.RESULT))\n"""

    Generated(v.toString, p.toString, regList)

  private def literal(v: Any): String = v match
    case d: Double  => (d.toLong & 0xFFFFFFFFL).toString
    case b: Boolean => if b then "32'h1" else "32'h0"
    case null       => "32'h0"
    case other      => sys.error(s"Unsupported literal: $other")

  private def binOp(op: Token): String = op.tokenType match
    case PLUS => "+" ; case MINUS => "-" ; case STAR => "*" ; case SLASH => "/"
    case GREATER => ">" ; case GREATER_EQUAL => ">=" ; case LESS => "<" ; case LESS_EQUAL => "<="
    case EQUAL_EQUAL => "==" ; case BANG_EQUAL => "!="
    case other => sys.error(s"Unsupported binary op $other")
