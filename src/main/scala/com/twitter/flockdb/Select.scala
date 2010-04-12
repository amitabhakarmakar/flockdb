package com.twitter.flockdb

import java.util.{List => JList}
import com.twitter.gizzard.thrift.conversions.Sequences._
import com.twitter.service.flock.State
import com.twitter.xrayspecs.Time
import com.twitter.xrayspecs.TimeConversions._
import conversions.ExecuteOperation._
import conversions.ExecuteOperations._
import conversions.Priority._
import conversions.SelectOperation._
import operations.SelectOperationType._
import operations._


object Select {
  def apply(sourceId: Unit, graphId: Int, destinationId: Long) = {
    new SimpleSelect(new SelectOperation(SimpleQuery, Some(new QueryTerm(destinationId, graphId, false, None, List(State.Normal)))))
  }

  def apply(sourceId: Long, graphId: Int, destinationId: Unit) = {
    new SimpleSelect(new SelectOperation(SimpleQuery, Some(new QueryTerm(sourceId, graphId, true, None, List(State.Normal)))))
  }

  def apply(sourceId: Long, graphId: Int, destinationId: Long) = {
    new SimpleSelect(new SelectOperation(SimpleQuery, Some(new QueryTerm(sourceId, graphId, true, Some(List[Long](destinationId)), List(State.Normal)))))
  }

  def apply(sourceId: Long, graphId: Int, destinationIds: Seq[Long]) = {
    new SimpleSelect(new SelectOperation(SimpleQuery, Some(new QueryTerm(sourceId, graphId, true, Some(destinationIds), List(State.Normal)))))
  }

  def apply(sourceIds: Seq[Long], graphId: Int, destinationId: Long) = {
    new SimpleSelect(new SelectOperation(SimpleQuery, Some(new QueryTerm(destinationId, graphId, false, Some(sourceIds), List(State.Normal)))))
  }
}

trait Select {
  def toThrift: JList[thrift.SelectOperation] = toList.toJavaList
  def toList: List[thrift.SelectOperation]
  def intersect(that: Select): Select = new CompoundSelect(Intersection, this, that)
}

trait Execute {
  def toThrift: thrift.ExecuteOperations
  def toOperations: List[thrift.ExecuteOperation]
  def at(time: Time): Execute
  def +(execute: Execute): Execute
}

// FIXME this is infinity-select not null :)
object NullSelect extends Select {
  override def intersect(that: Select) = that
  def toList = { throw new Exception("Not Applicable") }
}

case class SimpleSelect(operation: SelectOperation) extends Select {
  def toList = List(operation.toThrift)

  def addAt(at: Time) = execute(ExecuteOperationType.Add, at)
  def add = addAt(Time.now)
  def archiveAt(at: Time) = execute(ExecuteOperationType.Archive, at)
  def archive = archiveAt(Time.now)
  def removeAt(at: Time) = execute(ExecuteOperationType.Remove, at)
  def remove = removeAt(Time.now)
  def negateAt(at: Time) = execute(ExecuteOperationType.Negate, at)
  def negate = negateAt(Time.now)
  private def execute(executeOperationType: ExecuteOperationType.Value, at: Time) =
    new SimpleExecute(new ExecuteOperation(executeOperationType, operation.term.get,
                                           Some(Time.now.inMillis)), at)

  def negative = {
    val negativeOperation = operation.clone
    negativeOperation.term.get.states = List(State.Negative)
    new SimpleSelect(negativeOperation)
  }

  def states(states: State*) = {
    val statefulOperation = operation.clone
    statefulOperation.term.get.states = states
    new SimpleSelect(statefulOperation)
  }
}

case class CompoundSelect(operation: SelectOperationType.Value, operand1: Select, operand2: Select) extends Select {
  def toList = operand1.toList ++ operand2.toList ++ List(new SelectOperation(operation, None).toThrift)
}

case class SimpleExecute(operation: ExecuteOperation, at: Time) extends Execute {
  def toThrift = {
    val rv = new thrift.ExecuteOperations(toOperations.toJavaList, thrift.Priority.High)
    rv.setExecute_at(at.inSeconds)
    rv
  }
  def toOperations = List(operation.toThrift)
  def at(time: Time) = new SimpleExecute(operation, time)
  def +(execute: Execute) = new CompoundExecute(this, execute, at, Priority.High)
}

case class CompoundExecute(operand1: Execute, operand2: Execute, at: Time, priority: Priority.Value) extends Execute {
  def toThrift = {
    val rv = new thrift.ExecuteOperations(toOperations.toJavaList, priority.toThrift)
    rv.setExecute_at(at.inSeconds)
    rv
  }
  def toOperations = operand1.toOperations ++ operand2.toOperations

  def +(execute: Execute) = new CompoundExecute(this, execute, at, priority)
  def withPriority(priority: Priority.Value) = new CompoundExecute(operand2, operand2, at, priority)
  def at(time: Time) = new CompoundExecute(operand1, operand2, time, priority)
}
