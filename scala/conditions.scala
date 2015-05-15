object TestClass {
	def main(args: Array[String]) {
		method(true)
	}
	def method(flag : Boolean) {
		if (flag)
			println("true")
		else {
			println("false");
			while (flag) {
				println("while_loop")
			}
		}
	}
}
