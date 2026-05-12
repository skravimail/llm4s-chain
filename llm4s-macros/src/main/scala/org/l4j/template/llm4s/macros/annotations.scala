package org.l4j.template.llm4s.macros

import scala.annotation.StaticAnnotation

final class system(val value: String) extends StaticAnnotation

final class user(val value: String) extends StaticAnnotation

final class tool(val value: String) extends StaticAnnotation

final class param(val value: String) extends StaticAnnotation
