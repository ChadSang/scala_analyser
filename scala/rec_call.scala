object TestClass {
	def main(args: Array[String]) {
		method1()
	}
	def method1() {
		println("method1 called");
		method2()
	}
	def method2() {
		println("method2 called");
		method3()
	}
	def method3() {
		println("method3 called");
		method1()
	}
}
