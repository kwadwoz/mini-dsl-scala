import java.nio.file.{Files, Path}
import scala.sys.process.*

// Equivalence check: for many input vectors, confirm the generated FPGA design
// (simulated with iverilog) produces the SAME result as the interpreter.
object Verify:
  // Word address (byte offset >> 2) for a named register in the generated map.
  private def word(regs: List[RegInfo], name: String): Int =
    regs.find(_.name == name).map(_.byteOffset >> 2).getOrElse(
      sys.error(s"register $name not found"))

  // Build a self-checking testbench that runs every vector and prints results.
  private def testbench(regs: List[RegInfo], inputs: List[String],
                        vectors: List[List[Long]]): String =
    val ctrl = word(regs, "ctrl")
    val status = word(regs, "status")
    val result = word(regs, "result")
    val sb = StringBuilder()
    sb ++= "`timescale 1ns/1ps\nmodule tb;\n"
    sb ++= "  reg clk=0, rst=1;\n  reg wb_cyc=0, wb_stb=0, wb_we=0;\n"
    sb ++= "  reg [8:0] wb_adr=0; reg [31:0] wb_dat_w=0; reg [3:0] wb_sel=4'hF;\n"
    sb ++= "  wire [31:0] wb_dat_r; wire wb_ack;\n"
    sb ++= "  top dut(.clk(clk),.rst(rst),.wb_cyc(wb_cyc),.wb_stb(wb_stb),.wb_we(wb_we),\n"
    sb ++= "          .wb_adr(wb_adr),.wb_dat_w(wb_dat_w),.wb_sel(wb_sel),\n"
    sb ++= "          .wb_dat_r(wb_dat_r),.wb_ack(wb_ack));\n"
    sb ++= "  always #5 clk = ~clk;\n"
    sb ++= "  task wb_write(input [8:0] a, input [31:0] d); begin\n"
    sb ++= "    @(negedge clk); wb_adr=a; wb_dat_w=d; wb_we=1; wb_cyc=1; wb_stb=1;\n"
    sb ++= "    @(posedge clk); while(!wb_ack) @(posedge clk);\n"
    sb ++= "    @(negedge clk); wb_cyc=0; wb_stb=0; wb_we=0; end endtask\n"
    sb ++= "  task wb_read(input [8:0] a, output [31:0] d); begin\n"
    sb ++= "    @(negedge clk); wb_adr=a; wb_we=0; wb_cyc=1; wb_stb=1;\n"
    sb ++= "    @(posedge clk); while(!wb_ack) @(posedge clk);\n"
    sb ++= "    d=wb_dat_r; @(negedge clk); wb_cyc=0; wb_stb=0; end endtask\n"
    sb ++= "  reg [31:0] st, res;\n  integer k;\n"
    sb ++= "  initial begin #2000000 $display(\"WATCHDOG timeout\"); $finish; end\n"
    sb ++= "  initial begin\n"
    sb ++= "    repeat(4) @(posedge clk); rst=0;\n"
    vectors.zipWithIndex.foreach { (vec, vi) =>
      inputs.zip(vec).foreach { (name, value) =>
        sb ++= s"    wb_write(9'd${word(regs, name)}, 32'd$value);\n"
      }
      sb ++= s"    wb_write(9'd$ctrl, 32'd1);\n"
      sb ++= s"    st=0; while(!(st&1)) wb_read(9'd$status, st);\n"
      sb ++= s"    wb_read(9'd$result, res);\n"
      sb ++= s"""    $$display("RES $vi %0d", res);\n"""
    }
    sb ++= "    $finish;\n  end\nendmodule\n"
    sb.toString

  private def tool(name: String): String =
    val home = sys.env.getOrElse("HOME", "")
    val candidates = List(name, s"$home/oss-cad-suite/bin/$name")
    val devnull = ProcessLogger(_ => (), _ => ())
    def works(c: String): Boolean =
      try Process(Seq(c, "-V")).!(devnull) == 0 catch case _: Throwable => false
    candidates.find(works).getOrElse(name)

  // Returns true if every vector matched.
  def run(statements: List[Stmt], gen: Generated, work: Path = Path.of(".")): Boolean =
    val inputs = gen.regs.filter(r => r.dir == "in" && r.name != "ctrl").map(_.name)

    // Test vectors: small values plus a couple of larger ones, deterministic.
    val samples = List(0L, 1L, 2L, 3L, 5L, 7L, 10L, 13L)
    val rnd = new scala.util.Random(42)
    val vectors: List[List[Long]] =
      if inputs.isEmpty then List(Nil)
      else (0 until 12).map(_ => inputs.map(_ => samples(rnd.nextInt(samples.size)))).toList.distinct

    // Golden results from the interpreter. `None` = program did not terminate.
    val expected: List[Option[Long]] = vectors.map { vec =>
      try Some(Interpreter.run(statements, inputs.zip(vec).toMap))
      catch case _: Interpreter.NonTerminating => None
    }

    // Write design + testbench, compile, simulate.
    val designPath = work.resolve("design.v")
    val tbPath = work.resolve("tb_verify.v")
    Files.writeString(designPath, gen.verilog)
    Files.writeString(tbPath, testbench(gen.regs, inputs, vectors))

    val simOut = work.resolve("sim_verify").toString
    val iverilog = tool("iverilog")
    val vvp = tool("vvp")
    val compile = Process(Seq(iverilog, "-g2012", "-o", simOut,
      designPath.toString, tbPath.toString)).!
    if compile != 0 then
      println("  [verify] iverilog compile failed"); return false

    val actual = collection.mutable.Map.empty[Int, Long]
    val logger = ProcessLogger { line =>
      val m = raw"RES (\d+) (\d+)".r
      line match
        case m(i, v) => actual(i.toInt) = v.toLong
        case _       => ()
    }
    Process(Seq(vvp, simOut)).!(logger)

    // Compare.
    println(f"  ${"inputs"}%-24s ${"expected"}%10s ${"fpga"}%10s   result")
    var allOk = true
    vectors.zipWithIndex.foreach { (vec, i) =>
      val inStr = inputs.zip(vec).map((n, v) => s"$n=$v").mkString(", ")
      expected(i) match
        case None =>
          // Interpreter didn't terminate — skip (can't compare).
          println(f"  $inStr%-24s ${"(loops)"}%10s ${"—"}%10s   SKIP")
        case Some(exp) =>
          val got = actual.getOrElse(i, -1L)
          val ok = got == exp
          if !ok then allOk = false
          println(f"  $inStr%-24s $exp%10d $got%10d   ${if ok then "OK" else "MISMATCH"}")
    }
    allOk
