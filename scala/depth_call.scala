object TestClass {
	def main(args: Array[String]) {
		method1(true)
	}
	def method1(flag:Boolean) {
		println("method1 called");
		if (flag) 
			method2()
		else 
			println("false")
	}
	def method2() {
		println("method2 called")
	}
}
