// Ported from https://github.com/apple/pkl (Apache-2.0).
package org.pkl.codegen.scala

/** Thrown when a Pkl schema cannot be represented as Scala 3 code. */
final class ScalaCodeGeneratorException(message: String)
    extends RuntimeException(message)
