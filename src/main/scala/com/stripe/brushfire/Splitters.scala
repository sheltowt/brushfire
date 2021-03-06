package com.stripe.brushfire

import com.twitter.algebird._

case class BinarySplitter[V, T: Monoid](partition: V => Predicate[V])
    extends Splitter[V, T] {

  type S = Map[V, T]
  def create(value: V, target: T) = Map(value -> target)

  val semigroup = implicitly[Semigroup[Map[V, T]]]

  def split(parent: T, stats: Map[V, T]) = {
    stats.keys.map { v =>
      val predicate = partition(v)
      val (trues, falses) = stats.partition { case (v, d) => predicate(Some(v)) }
      BinarySplit(predicate, Monoid.sum(trues.values), Monoid.sum(falses.values))
    }
  }
}

case class QTreeSplitter[T: Monoid](k: Int)
    extends Splitter[Double, T] {

  type S = QTree[T]

  val semigroup = new QTreeSemigroup[T](k)
  def create(value: Double, target: T) = QTree(value -> target)

  def split(parent: T, stats: QTree[T]) = {
    findAllThresholds(stats).map { threshold =>
      val predicate = LessThan(threshold)
      val leftDist = stats.rangeSumBounds(stats.lowerBound, threshold)._1
      val rightDist = stats.rangeSumBounds(threshold, stats.upperBound)._1
      BinarySplit(predicate, leftDist, rightDist)
    }
  }

  def findAllThresholds(node: QTree[T]): Iterable[Double] = {
    node.lowerChild.toList.flatMap { l => findAllThresholds(l) } ++
      node.upperChild.toList.flatMap { u => findAllThresholds(u) } ++
      findThreshold(node).toList
  }

  def findThreshold(node: QTree[T]): Option[Double] = {
    for (l <- node.lowerChild; u <- node.upperChild)
      yield l.upperBound
  }
}

case class SparseBooleanSplitter[T: Group]
    extends Splitter[Boolean, T] {

  type S = T
  def create(value: Boolean, target: T) = target

  val semigroup = implicitly[Semigroup[T]]

  def split(parent: T, stats: T) =
    Some(BinarySplit(EqualTo(true), stats, Group.minus(parent, stats)))
}

case class SpaceSaverSplitter[V, L](capacity: Int = 1000)
    extends Splitter[V, Map[L, Long]] {

  type S = Map[L, SpaceSaver[V]]
  val semigroup = implicitly[Semigroup[S]]

  def create(value: V, target: Map[L, Long]) = target.mapValues { c =>
    Semigroup.sumOption(1L.to(c).map { i => SpaceSaver(capacity, value) }).get
  }

  def split(parent: Map[L, Long], stats: S) = {
    stats
      .values
      .flatMap { _.counters.keys }.toSet
      .map { v: V =>
        val mins = stats.mapValues { ss => ss.frequency(v).min }
        BinarySplit(EqualTo(v), mins, Group.minus(parent, mins))
      }
  }
}

case class BinarySplit[V, T](
  predicate: Predicate[V],
  leftDistribution: T,
  rightDistribution: T)
    extends Split[V, T] {
  def predicates =
    List(predicate -> leftDistribution, Not(predicate) -> rightDistribution)
}
