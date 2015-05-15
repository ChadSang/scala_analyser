object TestClass {
	def main(args: Array[String]) {
		val pt = new Point(1,2);
		pt.move(10, 10);
		println(pt);
		move(10, 10)
	}
	class Point(xc: Int, yc: Int) {
		var x: Int = xc
		var y: Int = yc
		def move(dx: Int, dy: Int) {
			x = x + dx
			y = y + dy
		}
		override def toString(): String = "(" + x + ", " + y + ")";
	}
	def move(dx : Int, dy : Int) {
		dx + dy
	}
}