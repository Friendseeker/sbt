lazy val previousCompileIsEmpty = taskKey[Unit]("")
lazy val previousCompileIsNonEmpty = taskKey[Unit]("")

previousCompileIsEmpty := {
  val previous = (Test / previousCompile).value
  assert(previous.analysis.isEmpty())
  assert(previous.setup.isEmpty())
}

previousCompileIsNonEmpty := {
  val previous = (Test / previousCompile).value
  assert(!previous.analysis.isEmpty())
  assert(!previous.setup.isEmpty())
}
