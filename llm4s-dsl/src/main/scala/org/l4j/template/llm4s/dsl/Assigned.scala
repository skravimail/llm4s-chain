package org.l4j.template.llm4s.dsl

final case class Assigned[+In, +Value](
    input: In,
    value: Value,
):
  def mapInput[Next](f: In => Next): Assigned[Next, Value] =
    Assigned(f(input), value)

  def mapValue[Next](f: Value => Next): Assigned[In, Next] =
    Assigned(input, f(value))

  def merge[Out](f: (In, Value) => Out): Out =
    f(input, value)
