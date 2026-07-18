// Testbench: drives the Wishbone slave like the Python SDK would.
// Writes N, pulses CTRL.start, polls STATUS.done, reads RESULT.
`timescale 1ns/1ps
module tb;
  reg clk = 0, rst = 1;
  reg wb_cyc = 0, wb_stb = 0, wb_we = 0;
  reg [8:0]  wb_adr = 0;
  reg [31:0] wb_dat_w = 0;
  reg [3:0]  wb_sel = 4'hF;
  wire [31:0] wb_dat_r;
  wire wb_ack;

  top dut(.clk(clk), .rst(rst), .wb_cyc(wb_cyc), .wb_stb(wb_stb), .wb_we(wb_we),
          .wb_adr(wb_adr), .wb_dat_w(wb_dat_w), .wb_sel(wb_sel),
          .wb_dat_r(wb_dat_r), .wb_ack(wb_ack));

  always #5 clk = ~clk; // 100 MHz

  // Word addresses (byte offset >> 2).
  localparam CTRL = 9'd0, STATUS = 9'd1, N = 9'd4, RESULT = 9'd16;

  task wb_write(input [8:0] adr, input [31:0] data);
  begin
    @(negedge clk); wb_adr = adr; wb_dat_w = data; wb_we = 1; wb_cyc = 1; wb_stb = 1;
    @(posedge clk); while (!wb_ack) @(posedge clk);
    @(negedge clk); wb_cyc = 0; wb_stb = 0; wb_we = 0;
  end
  endtask

  task wb_read(input [8:0] adr, output [31:0] data);
  begin
    @(negedge clk); wb_adr = adr; wb_we = 0; wb_cyc = 1; wb_stb = 1;
    @(posedge clk); while (!wb_ack) @(posedge clk);
    data = wb_dat_r;
    @(negedge clk); wb_cyc = 0; wb_stb = 0;
  end
  endtask

  integer nval;
  reg [31:0] status, res;
  initial begin
    repeat (4) @(posedge clk);
    rst = 0;

    for (nval = 0; nval <= 5; nval = nval + 1) begin
      wb_write(N, nval);
      wb_write(CTRL, 1);           // pulse start
      status = 0;
      while (!(status & 1)) wb_read(STATUS, status);  // wait for done
      wb_read(RESULT, res);
      $display("n=%0d  ->  sum(0..n-1) = %0d  (expected %0d)  %s",
               nval, res, nval*(nval-1)/2,
               (res == nval*(nval-1)/2) ? "OK" : "FAIL");
    end
    $finish;
  end
endmodule
