package my

import org.pkl.core.DataSize
import org.pkl.core.Duration
import scala.util.matching.Regex

case class Types(b: Boolean, i: Long, f: Double, n: Double, s: String, d: Duration, ds: DataSize, pair: (String, Long), coll: Iterable[String], set: Set[String], bytes: Array[Byte], regex: Regex, any: Any, nonNull: Any, listing: List[String], mapping: Map[String, Long]):
  override def equals(other: Any): Boolean =
    if (this.asInstanceOf[AnyRef] eq other.asInstanceOf[AnyRef]) return true
    if (this.getClass != other.getClass) return false
    val o = other.asInstanceOf[Types]
    if (this.b != o.b) return false
    if (this.i != o.i) return false
    if (this.f != o.f) return false
    if (this.n != o.n) return false
    if (this.s != o.s) return false
    if (this.d != o.d) return false
    if (this.ds != o.ds) return false
    if (this.pair != o.pair) return false
    if (this.coll != o.coll) return false
    if (this.set != o.set) return false
    if (!java.util.Arrays.equals(this.bytes, o.bytes)) return false
    if (this.regex.pattern != o.regex.pattern) return false
    if (this.any != o.any) return false
    if (this.nonNull != o.nonNull) return false
    if (this.listing != o.listing) return false
    if (this.mapping != o.mapping) return false
    true
  override def hashCode(): Int =
    var result = 1
    result = 31 * result + java.util.Objects.hashCode(this.b)
    result = 31 * result + java.util.Objects.hashCode(this.i)
    result = 31 * result + java.util.Objects.hashCode(this.f)
    result = 31 * result + java.util.Objects.hashCode(this.n)
    result = 31 * result + java.util.Objects.hashCode(this.s)
    result = 31 * result + java.util.Objects.hashCode(this.d)
    result = 31 * result + java.util.Objects.hashCode(this.ds)
    result = 31 * result + java.util.Objects.hashCode(this.pair)
    result = 31 * result + java.util.Objects.hashCode(this.coll)
    result = 31 * result + java.util.Objects.hashCode(this.set)
    result = 31 * result + java.util.Arrays.hashCode(this.bytes)
    result = 31 * result + java.util.Objects.hashCode(this.regex.pattern)
    result = 31 * result + java.util.Objects.hashCode(this.any)
    result = 31 * result + java.util.Objects.hashCode(this.nonNull)
    result = 31 * result + java.util.Objects.hashCode(this.listing)
    result = 31 * result + java.util.Objects.hashCode(this.mapping)
    result
  override def toString: String = s"""Types(b=$b, i=$i, f=$f, n=$n, s=$s, d=$d, ds=$ds, pair=$pair, coll=$coll, set=$set, bytes=${java.util.Arrays.toString(bytes)}, regex=$regex, any=$any, nonNull=$nonNull, listing=$listing, mapping=$mapping)"""
