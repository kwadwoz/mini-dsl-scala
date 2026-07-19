import java.nio.file.Path
import scala.sys.process.*

// Optional build step: run Yosys ECP5 synthesis on design.v and print a small
// utilization summary (LUT4 / flip-flops / etc.), so every build shows whether
// the design fits the chip. Skips gracefully if Yosys isn't installed.
object Synth:
  private def yosysPath: Option[String] =
    val home = sys.env.getOrElse("HOME", "")
    val candidates = List("yosys", s"$home/oss-cad-suite/bin/yosys")
    val devnull = ProcessLogger(_ => (), _ => ())
    candidates.find(c => try Process(Seq(c, "-V")).!(devnull) == 0 catch case _: Throwable => false)

  // ECP5-85F has ~83,640 LUTs available.
  private val Ecp5Luts = 83640

  def report(design: Path = Path.of("design.v")): Unit =
    yosysPath match
      case None =>
        println("  (yosys not found — skipping ECP5 synth report)")
      case Some(yosys) =>
        val out = StringBuilder()
        val logger = ProcessLogger(l => out ++= l + "\n", _ => ())
        val script = s"read_verilog ${design}; synth_ecp5; stat"
        val code = Process(Seq(yosys, "-p", script)).!(logger)
        if code != 0 then
          println("  (yosys synth failed)")
          return
        val text = out.toString
        // Pull "<count>   <CELLTYPE>" lines from the final stat block.
        val cellRe = raw"\s*(\d+)\s+([A-Z][A-Z0-9_]+)".r
        val cells = cellRe.findAllMatchIn(text)
          .map(m => m.group(2) -> m.group(1).toInt)
          .toList
          .groupMapReduce(_._1)(_._2)(_ max _) // dedupe (stat prints twice)
        val lut = cells.getOrElse("LUT4", 0)
        val ff  = cells.getOrElse("TRELLIS_FF", 0)
        val pct = 100.0 * lut / Ecp5Luts
        println(f"  LUT4 = $lut%-6d  FF = $ff%-6d  (~$pct%.2f%% of ECP5-85F)")
        val interesting = List("CCU2C", "MULT18X18D", "DP16KD", "L6MUX21", "PFUMX")
        val extra = interesting.flatMap(k => cells.get(k).map(v => s"$k=$v"))
        if extra.nonEmpty then println("  cells: " + extra.mkString(", "))
