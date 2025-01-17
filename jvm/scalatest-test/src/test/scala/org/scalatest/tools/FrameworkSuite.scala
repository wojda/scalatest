/*
 * Copyright 2001-2013 Artima, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.scalatest.tools
import org.scalatest.{Resources, Retries, OptionValues}
import org.scalatest.funsuite.AnyFunSuite
import sbt.testing.{Framework => _, _}
import org.scalatest.SharedHelpers.{EventRecordingReporter, createTempDirectory}
import org.scalatest.exceptions.NotAllowedException
import org.scalatest.tagobjects.Retryable
import java.io.File
import Retries._
import OptionValues._
import org.scalatest.tagobjects.Flicker

class FrameworkSuite extends AnyFunSuite {

  override def withFixture(test: NoArgTest) = {
    if (isRetryable(test))
      withRetry { super.withFixture(test) }
    else
      super.withFixture(test)
  }

  class TestEventHandler extends EventHandler {

    private var errorEvents = List[Event]()
    private var failureEvents = List[Event]()
    private var skippedEvents = List[Event]()
    private var successEvents = List[Event]()
    private var ignoredEvents = List[Event]()
    private var pendingEvents = List[Event]()
    private var canceledEvents = List[Event]()
    
    override def handle(event: Event): Unit = {
      event.status match {
        case Status.Success => successEvents ::= event
        case Status.Error => errorEvents ::= event
        case Status.Failure => failureEvents ::= event
        case Status.Skipped => skippedEvents ::= event
        case Status.Ignored => ignoredEvents ::= event
        case Status.Pending => pendingEvents ::= event
        case Status.Canceled => canceledEvents ::= event
      }
    }
    
    def errorEventsReceived = errorEvents.reverse
    def failureEventsReceived = failureEvents.reverse
    def skippedEventsReceived = skippedEvents.reverse
    def successEventsReceived = successEvents.reverse
    def ignoredEventsReceived = ignoredEvents.reverse
    def pendingEventsReceived = pendingEvents.reverse
    def canceledEventsReceived = canceledEvents.reverse
  }
  
  class TestLogger extends Logger {
    
    private var errorList = List[String]()
    private var warnList = List[String]()
    private var infoList = List[String]()
    private var debugList = List[String]()
    private var traceList = List[Throwable]()
    
    def ansiCodesSupported = false
    def error(msg: String): Unit = {
      errorList ::= msg
    }
    def warn(msg: String): Unit = {
      warnList ::= msg
    }
    def info(msg: String): Unit = {
      infoList ::= msg
    }
    def debug(msg: String): Unit = {
      debugList ::= msg
    }
    def trace(t: Throwable): Unit = {
      traceList ::= t
    }
    
    def errorReceived = errorList.reverse
    def warnReceived = warnList.reverse
    def infoReceived = infoList.reverse
    def debugReceived = debugList.reverse
    def traceReceived = traceList.reverse
  }

  test("framework name") {
    assert(new Framework().name === "ScalaTest")
  }
  
  test("fingerprints contains 2 test fingerprints, they are SubclassFingerprint for org.scalatest.Suite and AnnotatedFingerprint for org.scalatest.WrapWith") {
    val framework = new Framework
    val fingerprints = framework.fingerprints
    assert(fingerprints.size === 2)

    val testFingerprint =
      fingerprints(0).asInstanceOf[sbt.testing.SubclassFingerprint]
    assert(testFingerprint.isModule === false)
    assert(testFingerprint.superclassName === "org.scalatest.Suite")
    assert(testFingerprint.requireNoArgConstructor === true)
    
    val annotatedFingerprint = 
      fingerprints(1).asInstanceOf[sbt.testing.AnnotatedFingerprint]
    assert(annotatedFingerprint.isModule === false)
    assert(annotatedFingerprint.annotationName === "org.scalatest.WrapWith")
  }
  
  val testClassLoader = getClass.getClassLoader
  val subClassFingerprint = new sbt.testing.SubclassFingerprint {
                              def superclassName = "org.scalatest.Suite"
                              def isModule = false
                              def requireNoArgConstructor = true
                            }
  
  val framework = new Framework
  val subclassFingerprint = 
    new SubclassFingerprint {
      def superclassName = "org.scalatest.Suite"
      def isModule = false
      def requireNoArgConstructor = true
    }
  val annotatedFingerprint = 
    new AnnotatedFingerprint {
      def annotationName = "org.scalatest.WrapWith"
      def isModule = false
    }
  
  def assertSuiteSuccessEvent(event: Event, suiteClassName: String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Success === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case testSelector: TestSelector => 
        assert(testName === testSelector.testName)
      case _ => 
        fail("Expected to get TestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertNestedSuiteSuccessEvent(event: Event, suiteClassName: String, suiteId:String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Success === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case nestedTestSelector: NestedTestSelector => 
        assert(suiteId === nestedTestSelector.suiteId)
        assert(testName === nestedTestSelector.testName)
      case _ => 
        fail("Expected to get NestedTestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertSuiteFailureEvent(event: Event, suiteClassName: String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Failure === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case testSelector: TestSelector => 
        assert(testName === testSelector.testName)
      case _ => 
        fail("Expected to get TestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertNestedSuiteFailureEvent(event: Event, suiteClassName: String, suiteId:String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Failure === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case nestedTestSelector: NestedTestSelector => 
        assert(suiteId === nestedTestSelector.suiteId)
        assert(testName === nestedTestSelector.testName)
      case _ => 
        fail("Expected to get NestedTestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertSuiteErrorEvent(event: Event, suiteClassName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Error === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case suiteSelector: SuiteSelector => 
        // Nothing more to check, just make sure it is SuiteSelector.
      case _ => 
        fail("Expected to get TestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertNestedSuiteErrorEvent(event: Event, suiteClassName: String, suiteId:String, fingerprint: Fingerprint): Unit = {
    assert(Status.Error === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case nestedSuiteSelector: NestedSuiteSelector => 
        assert(suiteId === nestedSuiteSelector.suiteId)
      case _ => 
        fail("Expected to get NestedTestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertSuiteSkippedEvent(event: Event, suiteClassName: String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Skipped === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case testSelector: TestSelector => 
        assert(testName === testSelector.testName)
      case _ => 
        fail("Expected to get TestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertSuiteIgnoredEvent(event: Event, suiteClassName: String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Ignored === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration === -1)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case testSelector: TestSelector => 
        assert(testName === testSelector.testName)
      case _ => 
        fail("Expected to get TestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertSuitePendingEvent(event: Event, suiteClassName: String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Pending === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case testSelector: TestSelector => 
        assert(testName === testSelector.testName)
      case _ => 
        fail("Expected to get TestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertSuiteCanceledEvent(event: Event, suiteClassName: String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Canceled === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case testSelector: TestSelector => 
        assert(testName === testSelector.testName)
      case _ => 
        fail("Expected to get TestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertNestedSuiteSkippedEvent(event: Event, suiteClassName: String, suiteId:String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Skipped === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case nestedTestSelector: NestedTestSelector => 
        assert(suiteId === nestedTestSelector.suiteId)
        assert(testName === nestedTestSelector.testName)
      case _ => 
        fail("Expected to get NestedTestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertNestedSuiteIgnoredEvent(event: Event, suiteClassName: String, suiteId:String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Ignored === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration === -1)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case nestedTestSelector: NestedTestSelector => 
        assert(suiteId === nestedTestSelector.suiteId)
        assert(testName === nestedTestSelector.testName)
      case _ => 
        fail("Expected to get NestedTestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertNestedSuitePendingEvent(event: Event, suiteClassName: String, suiteId:String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Pending === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case nestedTestSelector: NestedTestSelector => 
        assert(suiteId === nestedTestSelector.suiteId)
        assert(testName === nestedTestSelector.testName)
      case _ => 
        fail("Expected to get NestedTestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  def assertNestedSuiteCanceledEvent(event: Event, suiteClassName: String, suiteId:String, testName: String, fingerprint: Fingerprint): Unit = {
    assert(Status.Canceled === event.status)
    assert(suiteClassName === event.fullyQualifiedName)
    assert(fingerprint === event.fingerprint)
    assert(event.duration >= 0)
    assert(!event.throwable.isDefined)
    val selector = event.selector
    selector match {
      case nestedTestSelector: NestedTestSelector => 
        assert(suiteId === nestedTestSelector.suiteId)
        assert(testName === nestedTestSelector.testName)
      case _ => 
        fail("Expected to get NestedTestSelector, but got: " + selector.getClass.getName)
    }
  }
  
  test("ScalaTestRunner.task should return task that run whole suite when fullyQualifiedName = valid class name, explicitlySpecified = false and selectors = Array(SuiteSelector)") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 3)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SampleSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SampleSuite", "test 2", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(2), "org.scalatest.tools.scalasbt.SampleSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("ScalaTestRunner.task should return empty task array when fullyQualifiedName = valid class name, explicitlySpecified = false, selectors = Array(SuiteSelector)" +
  	   "and the suite class is marked as @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 0)
    }
    finally {
      runner.done()
    }
  }
  
  test("When suite is neither subclass of org.scalatest.Suite or annotated with WrapWith and explicitlySpecified is true, IllegalArgumentException will be thrown when task executes") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      intercept[IllegalArgumentException] {
        val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.NotASuite", subclassFingerprint, true, Array(new SuiteSelector))))
        assert(tasks.size === 1)
        val notASuiteTask = tasks(0)
        notASuiteTask.execute(new TestEventHandler, Array(new TestLogger))
      }
    }
    finally {
      runner.done()
    }
  }
  
  test("When suite is neither subclass of org.scalatest.Suite or annotated with WrapWith and explicitlySpecified is false, no task will be returned") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.NotASuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 0)
    }
    finally {
      runner.done()
    }
  }

  test("When an invalid suite class name is passed into to task(fullyQualifiedName: String, fingerprint: Fingerprint), IllegalArgumentException " +
  	   "will be thrown") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      intercept[IllegalArgumentException] {
        val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoesNotExist", subclassFingerprint, false, Array(new SuiteSelector))))
        assert(tasks.size === 1)
        val doesNotExistTask = tasks(0)
        doesNotExistTask.execute(new TestEventHandler, Array(new TestLogger))
      }
    }
    finally {
      runner.done()
    }
  }
  
  test("Nested suites will be executed in task(fullyQualifiedName: String, fingerprint: Fingerprint), no nested task will be returned") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SuiteWithNestedSuites", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val nestedTasks = task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 9)
      assertNestedSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 1", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 2", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(2), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 3", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(3), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 2", "nested 2 test 1", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(4), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 2", "nested 2 test 2", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(5), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 2", "nested 2 test 3", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(6), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(7), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "test 2", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(8), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
      assert(nestedTasks.size === 0)
    }
    finally {
      runner.done()
    }
  }
  
  test("Ignore, pending, failed, canceled, suite aborted events should be translated and reported correctly for the suite and its nested suites") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val nestedTasks = task.execute(testEventHandler, Array(new TestLogger))
      assert(nestedTasks.size == 0)

      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length == 3)
      assertNestedSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 1", "nested 1 success", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 2", "nested 2 success", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(2), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "success", subclassFingerprint)

      val failureEvents = testEventHandler.failureEventsReceived
      assert(failureEvents.length == 3)
      assertNestedSuiteFailureEvent(failureEvents(0), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 1", "nested 1 failed", subclassFingerprint)
      assertNestedSuiteFailureEvent(failureEvents(1), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 2", "nested 2 failed", subclassFingerprint)
      assertSuiteFailureEvent(failureEvents(2), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "failed", subclassFingerprint)

      val errorEvents = testEventHandler.errorEventsReceived
      assert(errorEvents.length == 1)
      assertNestedSuiteErrorEvent(errorEvents(0), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 3", subclassFingerprint)

      val skippedEvents = testEventHandler.skippedEventsReceived
      assert(skippedEvents.length == 0)

      val ignoredEvents = testEventHandler.ignoredEventsReceived
      assert(ignoredEvents.length == 3)
      assertNestedSuiteIgnoredEvent(ignoredEvents(0), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 1", "nested 1 ignored", subclassFingerprint)
      assertNestedSuiteIgnoredEvent(ignoredEvents(1), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 2", "nested 2 ignored", subclassFingerprint)
      assertSuiteIgnoredEvent(ignoredEvents(2), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "ignored", subclassFingerprint)

      val pendingEvents = testEventHandler.pendingEventsReceived
      assert(pendingEvents.length == 3)
      assertNestedSuitePendingEvent(pendingEvents(0), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 1", "nested 1 pending", subclassFingerprint)
      assertNestedSuitePendingEvent(pendingEvents(1), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 2", "nested 2 pending", subclassFingerprint)
      assertSuitePendingEvent(pendingEvents(2), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "pending", subclassFingerprint)

      val canceledEvents = testEventHandler.canceledEventsReceived
      assert(canceledEvents.length == 3)
      assertNestedSuiteCanceledEvent(canceledEvents(0), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 1", "nested 1 canceled", subclassFingerprint)
      assertNestedSuiteCanceledEvent(canceledEvents(1), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "nested 2", "nested 2 canceled", subclassFingerprint)
      assertSuiteCanceledEvent(canceledEvents(2), "org.scalatest.tools.scalasbt.SuiteWithFailedSkippedTests", "canceled", subclassFingerprint)
    }
    finally {
      runner.done()
    }
  }

  test("SuiteSelector should select and run test(s) in selected suite") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector()))))
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 3)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SampleSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SampleSuite", "test 2", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(2), "org.scalatest.tools.scalasbt.SampleSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }
  
  test("SuiteSelector should select and run test(s) in selected suite when it is explicitly specified, even when the selected suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, true, Array(new SuiteSelector()))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 3)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 2", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(2), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("TestSelector should select and run selected test(s) in suite, excluding nested suites") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new TestSelector("test 1"), new TestSelector("test 3"))), 
                                     new TaskDef("org.scalatest.tools.scalasbt.SuiteWithNestedSuites", subclassFingerprint, false, Array(new TestSelector("test 2")))))
      assert(tasks.size === 2)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SampleSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SampleSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    
      val testEventHandler2 = new TestEventHandler
      val task2 = tasks(1)
      val task2NestedSuites = task2.execute(testEventHandler2, Array(new TestLogger))
      val successEvents2 = testEventHandler2.successEventsReceived
      assert(successEvents2.length === 1)
      assertSuiteSuccessEvent(successEvents2(0), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "test 2", subclassFingerprint)
      assert(testEventHandler2.errorEventsReceived.length === 0)
      assert(testEventHandler2.failureEventsReceived.length === 0)
      assert(testEventHandler2.skippedEventsReceived.length === 0)

      assert(task2NestedSuites.size == 0)
    }
    finally {
      runner.done()
    }
  }

  test("TestSelector should select and run selected test(s) in suite when it is explicitly specified, even when the suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, true, Array(new TestSelector("test 1"), new TestSelector("test 3")))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("TestSelector should not select and run selected test(s) in suite when it is not explicitly specified and the suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, false, Array(new TestSelector("test 1"), new TestSelector("test 3")))))
      assert(tasks.size === 0)
    }
    finally {
      runner.done()
    }
  }

  test("TestWildcardSelector should select and run selected test(s) using wildcard in suite, excluding nested suites") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new TestWildcardSelector("est 1"), new TestWildcardSelector("st 3"))),
        new TaskDef("org.scalatest.tools.scalasbt.SuiteWithNestedSuites", subclassFingerprint, false, Array(new TestWildcardSelector("t 2")))))
      assert(tasks.size === 2)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SampleSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SampleSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)

      val testEventHandler2 = new TestEventHandler
      val task2 = tasks(1)
      val task2NestedSuites = task2.execute(testEventHandler2, Array(new TestLogger))
      val successEvents2 = testEventHandler2.successEventsReceived
      assert(successEvents2.length === 1)
      assertSuiteSuccessEvent(successEvents2(0), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "test 2", subclassFingerprint)
      assert(testEventHandler2.errorEventsReceived.length === 0)
      assert(testEventHandler2.failureEventsReceived.length === 0)
      assert(testEventHandler2.skippedEventsReceived.length === 0)

      assert(task2NestedSuites.size == 0)
    }
    finally {
      runner.done()
    }
  }

  test("TestWildcardSelector should select and run selected test(s) in suite when it is explicitly specified, even when the suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, true, Array(new TestWildcardSelector("st 1"), new TestWildcardSelector("est 3")))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("TestWildcardSelector should not select and run selected test(s) in suite when it is not explicitly specified and the suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, false, Array(new TestWildcardSelector("est 1"), new TestWildcardSelector("t 3")))))
      assert(tasks.size === 0)
    }
    finally {
      runner.done()
    }
  }
  
  test("NestedSuiteSelector should select and run test(s) in selected nested suite when it is explicitly specified, even if the selected nested suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SuiteWithNestedSuites", subclassFingerprint, true, Array(new NestedSuiteSelector("nested 1")))))
      assert(tasks.size == 1)
      val task = tasks(0)
      val nestedTasks = task.execute(testEventHandler, Array(new TestLogger))
      assert(nestedTasks.size == 0)
    
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length == 3)
      assertNestedSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 1", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 2", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(2), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 3", subclassFingerprint)

      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("NestedSuiteSelector should select and run test(s) in selected nested suite when it is not explicitly specified, even if the selected nested suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SuiteWithNestedSuites", subclassFingerprint, false, Array(new NestedSuiteSelector("nested 1")))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val nestedTasks = task.execute(testEventHandler, Array(new TestLogger))
      assert(nestedTasks.size === 0)
    
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 3)
      assertNestedSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 1", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 2", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(2), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 3", subclassFingerprint)

      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("NestedTestSelector should select and run selected test(s) in selected nested suite when it is explicitly specified, even if the selected nested suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SuiteWithNestedSuites", subclassFingerprint, false, Array(new NestedTestSelector("nested 1", "nested 1 test 1"), new NestedTestSelector("nested 2", "nested 2 test 3")))))
      assert(tasks.size == 1)
      val task = tasks(0)
      val nestedTasks = task.execute(testEventHandler, Array(new TestLogger))
      assert(nestedTasks.size == 0)

      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length == 2)
      assertNestedSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 1", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 2", "nested 2 test 3", subclassFingerprint)

      assert(testEventHandler.errorEventsReceived.length == 0)
      assert(testEventHandler.failureEventsReceived.length == 0)
      assert(testEventHandler.skippedEventsReceived.length == 0)
    }
    finally {
      runner.done()
    }
  }

  test("NestedTestSelector should select and run selected test(s) in selected nested suite when it is not explicitly specified, even if the selected nested suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SuiteWithNestedSuites", subclassFingerprint, false, Array(new NestedTestSelector("nested 1", "nested 1 test 1"), new NestedTestSelector("nested 2", "nested 2 test 3")))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val nestedTasks = task.execute(testEventHandler, Array(new TestLogger))
      assert(nestedTasks.size === 0)

      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertNestedSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 1", "nested 1 test 1", subclassFingerprint)
      assertNestedSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SuiteWithNestedSuites", "nested 2", "nested 2 test 3", subclassFingerprint)

      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("ScalaTestRunner should return summary when 'done' is called, and throw IllegalStateException if 'done' method is called twice.") {
    val runner = framework.runner(Array("-oW"), Array.empty, testClassLoader)
    
    try {
      val testLogger = new TestLogger
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector()))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(new TestEventHandler, Array(testLogger))
      val summaryText = runner.done.split("\n")
      assert(summaryText.size === 5)
      assert(summaryText(0).startsWith("Run completed in "))
      assert(summaryText(1) === "Total number of tests run: 3")
      assert(summaryText(2) === "Suites: completed 1, aborted 0")
      assert(summaryText(3) === "Tests: succeeded 3, failed 0, canceled 0, ignored 0, pending 0")
      assert(summaryText(4) === "All tests passed.")
      intercept[IllegalStateException] {
        runner.done()
      }
    }
    finally {
      try { // Just to make sure runner.done() has been called to avoid hanging thread
        runner.done()
      }
      catch {
        case _: IllegalStateException => // Do nothing
      }
    }
  }

  test("ScalaTestRunner using -oWI should return summary that contains failed and canceled test reminder when 'done' is called") {
    val runner = framework.runner(Array("-oWI"), Array.empty, testClassLoader)
    
    try {
      val testLogger = new TestLogger
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SuiteWithFailedCanceledTests", subclassFingerprint, false, Array(new SuiteSelector()))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(new TestEventHandler, Array(testLogger))
      val summaryText = runner.done.split("\n")
      assert(summaryText.size === 13)
      assert(summaryText(0).startsWith("Run completed in "))
      assert(summaryText(1) === "Total number of tests run: 2")
      assert(summaryText(2) === "Suites: completed 1, aborted 0")
      assert(summaryText(3) === "Tests: succeeded 1, failed 1, canceled 1, ignored 1, pending 1")
      assert(summaryText(4) === "*** 1 TEST FAILED ***")
      assert(summaryText(5) === "SuiteWithFailedCanceledTests:")
      assert(summaryText(6) === "")
      assert(summaryText(7) === "- failed *** FAILED ***")
      assert(summaryText(8) === "  org.scalatest.exceptions.TestFailedException was thrown. (SuiteWithFailedCanceledTests.scala:24)")
      assert(summaryText(9) === "SuiteWithFailedCanceledTests:")
      assert(summaryText(10) === "")
      assert(summaryText(11) === "- canceled !!! CANCELED !!!")
      assert(summaryText(12) === "  org.scalatest.exceptions.TestCanceledException was thrown. (SuiteWithFailedCanceledTests.scala:25)")
    }
    finally {
      try { // Just to make sure runner.done() has been called to avoid hanging thread
        runner.done()
      }
      catch {
        case _: IllegalStateException => // Do nothing
      }
    }
  }

  test("ScalaTestRunner using -oWIK should return summary that contains failed test reminder only (without canceled test) when 'done' is called") {
    val runner = framework.runner(Array("-oWIK"), Array.empty, testClassLoader)
    
    try {
      val testLogger = new TestLogger
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SuiteWithFailedCanceledTests", subclassFingerprint, false, Array(new SuiteSelector()))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(new TestEventHandler, Array(testLogger))
      val summaryText = runner.done.split("\n")
      assert(summaryText.size === 9)
      assert(summaryText(0).startsWith("Run completed in "))
      assert(summaryText(1) === "Total number of tests run: 2")
      assert(summaryText(2) === "Suites: completed 1, aborted 0")
      assert(summaryText(3) === "Tests: succeeded 1, failed 1, canceled 1, ignored 1, pending 1")
      assert(summaryText(4) === "*** 1 TEST FAILED ***")
      assert(summaryText(5) === "SuiteWithFailedCanceledTests:")
      assert(summaryText(6) === "")
      assert(summaryText(7) === "- failed *** FAILED ***")
      assert(summaryText(8) === "  org.scalatest.exceptions.TestFailedException was thrown. (SuiteWithFailedCanceledTests.scala:24)")
    }
    finally {
      try { // Just to make sure runner.done() has been called to avoid hanging thread
        runner.done()
      }
      catch {
        case _: IllegalStateException => // Do nothing
      }
    }
  }

  test("ScalaTest Task's tags method should return 'cpu' when suite class is annotated with @CPU") {
    val runner = framework.runner(Array("-oW"), Array.empty, testClassLoader)
    try {
      val testLogger = new TestLogger
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.CPUTaggedSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val taskTags = task.tags
      assert(taskTags.size === 1)
      assert(taskTags(0) === "cpu")
    }
    finally {
      runner.done()
    }
  }
  
  test("ScalaTest Task's tags method should return 'network' when suite class is annotated with @Network") {
    val runner = framework.runner(Array("-oW"), Array.empty, testClassLoader)
    try {
      val testLogger = new TestLogger
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.NetworkTaggedSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val taskTags = task.tags
      assert(taskTags.size === 1)
      assert(taskTags(0) === "network")
    }
    finally {
      runner.done()
    }
  }

  test("ScalaTest Task's tags method should return 'disk' when suite class is annotated with @Disk") {
    val runner = framework.runner(Array("-oW"), Array.empty, testClassLoader)
    try {
      val testLogger = new TestLogger
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DiskTaggedSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val taskTags = task.tags
      assert(taskTags.size === 1)
      assert(taskTags(0) === "disk")
    }
    finally {
      runner.done()
    }
  }

  test("ScalaTest Task's tags method should return 'custom' when suite class is annotated with @TagAnnotation('custom')") {
    val runner = framework.runner(Array("-oW"), Array.empty, testClassLoader)
    try {
      val testLogger = new TestLogger
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.CustomTaggedSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val taskTags = task.tags
      assert(taskTags.size === 1)
      assert(taskTags(0) === "custom")
    }
    finally {
      runner.done()
    }
  }
  
  test("ScalaTest Task's taskDef method should return TaskDef that defines the task") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    
    try {
      val testEventHandler = new TestEventHandler
      val suiteSelector = new SuiteSelector();
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array.empty), 
                                   new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, false, Array(suiteSelector)), 
                                   new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, true, Array(suiteSelector))))
                                   
      assert(tasks.length === 2)
    
      val task1 = tasks(0)
      val taskDef1 = task1.taskDef
      assert(taskDef1.fullyQualifiedName === "org.scalatest.tools.scalasbt.SampleSuite")
      assert(taskDef1.fingerprint === subclassFingerprint)
      assert(taskDef1.explicitlySpecified === false)
      assert(taskDef1.selectors.length === 0)
    
      val task2 = tasks(1)
      val taskDef2 = task2.taskDef
      assert(taskDef2.fullyQualifiedName === "org.scalatest.tools.scalasbt.DoNotDiscoverSuite")
      assert(taskDef2.fingerprint === subclassFingerprint)
      assert(taskDef2.explicitlySpecified === true)
      val task2Selectors = taskDef2.selectors
      assert(task2Selectors.length === 1)
      assert(task2Selectors(0) === suiteSelector)
    }
    finally {
      runner.done()
    }
  }

  test("-l argument can be used to exclude test") {
    val runner = framework.runner(Array("-l", "org.scalatest.tools.scalasbt.SampleSuite.SlowTest"), Array.empty, testClassLoader)
    
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SampleSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SampleSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }
  
  test("-n argument can be used to include test") {
    val runner = framework.runner(Array("-n", "org.scalatest.tools.scalasbt.SampleSuite.SlowTest"), Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
    
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 1)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SampleSuite", "test 2", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }
  
  test("-w should execute suites that match the specified package and its sub packages") {
    val runner = framework.runner(Array("-w", "org.scalatest.tools"), Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector)), 
                                     new TaskDef("org.scalatest.tools.FrameworkSuite", subclassFingerprint, false, Array(new SuiteSelector)), 
                                     new TaskDef("org.scalatest.SuiteSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 2)
    }
    finally {
      runner.done()
    }
  }

  test("-m should execute suites that match the specified package and not its sub packages") {
    val runner = framework.runner(Array("-m", "org.scalatest.tools"), Array.empty, testClassLoader)
    try {
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector)), 
                                     new TaskDef("org.scalatest.tools.FrameworkSuite", subclassFingerprint, false, Array(new SuiteSelector)), 
                                     new TaskDef("org.scalatest.SuiteSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val runner2 = framework.runner(Array("-m", "org.scalatest.concurrent"), Array.empty, testClassLoader)
      try {
        val tasks2 = runner2.tasks(Array(new TaskDef("org.scalatest.enablers.NoParamSpec", subclassFingerprint, false, Array(new SuiteSelector))))
        assert(tasks2.size === 0)
      }
      finally {
        runner2.done()
      }
    }
    finally {
      runner.done()
    }
  }
  
  // Now in 0.13.0-RC4 when there are 2 TaskDef with same class name different fingerprint, only one of it will be passed in.
  // We can't rely on fingerprint for this check anymore.
  /*test("a suite should be filtered out when fingerprint is subclassFingerprint and it is not accessible, even though it is annotated with @WrapWith") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    val tasks = runner.tasks(Array(new TaskDef("org.scalatest.SavesConfigMapSuite", subclassFingerprint, false, Array(new SuiteSelector))))
    assert(tasks.size === 0)
  }*/
  
  test("Framework.runner should throw IllegalArgumentException when -s is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-s", "org.scalatest.tools.scalasbt.SampleSuite"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "Specifying a suite (-s <suite>) or nested suite (-i <nested suite>) is not supported when running ScalaTest from sbt; Please use sbt's test-only instead.")
  }
  
  test("Framework.runner should throw IllegalArgumentException when -j is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-j", "org.scalatest.tools.scalasbt.SampleJUnitSuite"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "Running JUnit tests (-j <junit>) is not supported when running ScalaTest from sbt.")
  }

  test("Framework.runner should throw IllegalArgumentException when -b is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-b", "org.scalatest.tools.scalasbt.SampleSuite"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "Running TestNG tests (-b <testng>) is not supported when running ScalaTest from sbt.")
  }
  
  test("Framework.runner should throw IllegalArgumentException when -P is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-P"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "-P without specifying <numthreads> is not supported when running ScalaTest from sbt, please use sbt parallel configuration instead.")
  }

  test("Framework.runner accept without problem when -P4 is passed in") {
    val runner = framework.runner(Array("-P4"), Array.empty, testClassLoader)
    runner.done()
  }

  test("Framework.runner should throw IllegalArgumentException when -P0 is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-P0"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "-P with negative or zero thread number is invalid, please pass in a positive thread number instead.")
  }

  test("Framework.runner should throw IllegalArgumentException when -P-1 is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-P-1"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "-P with negative or zero thread number is invalid, please pass in a positive thread number instead.")
  }
  
  test("Framework.runner should throw IllegalArgumentException when -PS is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-PS"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "-PS is not supported when running ScalaTest from sbt, please use sbt parallel and logBuffered configuration instead.")
  }
  
  test("Framework.runner should throw IllegalArgumentException when -R is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-R"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "Specifying a runpath (-R <runpath>) is not supported when running ScalaTest from sbt.")
  }

  test("Framework.runner should throw IllegalArgumentException when -A is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-A", "again.txt"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "Run again (-A) is not supported when running ScalaTest from sbt; Please use sbt's test-quick instead.")
  }
  
  test("Framework.runner should throw IllegalArgumentException when -q is passed in") {
    val iae = intercept[IllegalArgumentException] {
      framework.runner(Array("-q", "Spec"), Array.empty, testClassLoader)
    }
    assert(iae.getMessage === "Discovery suffixes (-q) is not supported when running ScalaTest from sbt; Please use sbt's test-only or test filter instead.")
  }

  test("Framework.runner should be able to pass in test sorting timeout with -T") {
    val runner = framework.runner(Array("-T", "100"), Array.empty, testClassLoader)
    runner.done()
  }
  
  private def makeSureDone(runners: Runner*)(fun: => Unit): Unit = {
    try {
      fun
    }
    finally {
      runners.foreach { r => 
        try {
          r.done()
        }
        catch {
          case e: Throwable => // Just do nothing
        }
      }
    }
  }

  test("Framework.runner should be able to pass in custom reporter via -C", Retryable) {
    val runner = framework.runner(Array("-C", classOf[EventRecordingReporter].getName), Array.empty, testClassLoader)
    makeSureDone(runner) {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      scalatestRunner.done()
      scalatestRunner.dispatchReporter.reporters.find(_.isInstanceOf[EventRecordingReporter]) match {
        case Some(recordingRep : EventRecordingReporter) => 
          assert(recordingRep.testSucceededEventsReceived.size === 3)
        case _ => fail("Expected to find EventRecordingReporter, but not found.")
      }
    }
  }

  test("should fire SuiteAborted event when after function in BeforeAndAfter throws RuntimeException") {
    val runner = framework.runner(Array("-C", classOf[EventRecordingReporter].getName), Array.empty, testClassLoader)
    makeSureDone(runner) {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.FaulthyBeforeAndAfterSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      assert(testEventHandler.successEventsReceived.size === 1)
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      scalatestRunner.done()
      scalatestRunner.dispatchReporter.reporters.find(_.isInstanceOf[EventRecordingReporter]) match {
        case Some(recordingRep : EventRecordingReporter) =>
          assert(recordingRep.testSucceededEventsReceived.size === 1)
          assert(recordingRep.suiteCompletedEventsReceived.size === 0)
          assert(recordingRep.suiteAbortedEventsReceived.size === 1)
        case _ => fail("Expected to find EventRecordingReporter, but not found.")
      }
    }
  }

  test("should fire SuiteAborted event when afterAll function in BeforeAndAfterAll throws RuntimeException") {
    val runner = framework.runner(Array("-C", classOf[EventRecordingReporter].getName), Array.empty, testClassLoader)
    makeSureDone(runner) {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.FaulthyBeforeAndAfterAllSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      assert(testEventHandler.successEventsReceived.size === 1)
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      scalatestRunner.done()
      scalatestRunner.dispatchReporter.reporters.find(_.isInstanceOf[EventRecordingReporter]) match {
        case Some(recordingRep : EventRecordingReporter) =>
          assert(recordingRep.testSucceededEventsReceived.size === 1)
          assert(recordingRep.suiteCompletedEventsReceived.size === 0)
          assert(recordingRep.suiteAbortedEventsReceived.size === 1)
        case _ => fail("Expected to find EventRecordingReporter, but not found.")
      }
    }
  }

  test("should fire SuiteAborted event when afterAll function in BeforeAndAfterAllConfigMap throws RuntimeException") {
    val runner = framework.runner(Array("-C", classOf[EventRecordingReporter].getName), Array.empty, testClassLoader)
    makeSureDone(runner) {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.FaulthyBeforeAndAfterAllConfigMapSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      assert(testEventHandler.successEventsReceived.size === 1)
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      scalatestRunner.done()
      scalatestRunner.dispatchReporter.reporters.find(_.isInstanceOf[EventRecordingReporter]) match {
        case Some(recordingRep : EventRecordingReporter) =>
          assert(recordingRep.testSucceededEventsReceived.size === 1)
          assert(recordingRep.suiteCompletedEventsReceived.size === 0)
          assert(recordingRep.suiteAbortedEventsReceived.size === 1)
        case _ => fail("Expected to find EventRecordingReporter, but not found.")
      }
    }
  }

  test("should fire SuiteAborted event when afterEach function in BeforeAndAfterEach throws RuntimeException") {
    val runner = framework.runner(Array("-C", classOf[EventRecordingReporter].getName), Array.empty, testClassLoader)
    makeSureDone(runner) {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.FaulthyBeforeAndAfterEachSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      assert(testEventHandler.successEventsReceived.size === 1)
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      scalatestRunner.done()
      scalatestRunner.dispatchReporter.reporters.find(_.isInstanceOf[EventRecordingReporter]) match {
        case Some(recordingRep : EventRecordingReporter) =>
          assert(recordingRep.testSucceededEventsReceived.size === 1)
          assert(recordingRep.suiteCompletedEventsReceived.size === 0)
          assert(recordingRep.suiteAbortedEventsReceived.size === 1)
        case _ => fail("Expected to find EventRecordingReporter, but not found.")
      }
    }
  }

  test("should fire SuiteAborted event when afterEach function in BeforeAndAfterEachTestData throws RuntimeException") {
    val runner = framework.runner(Array("-C", classOf[EventRecordingReporter].getName), Array.empty, testClassLoader)
    makeSureDone(runner) {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.FaulthyBeforeAndAfterEachTestDataSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      assert(testEventHandler.successEventsReceived.size === 1)
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      scalatestRunner.done()
      scalatestRunner.dispatchReporter.reporters.find(_.isInstanceOf[EventRecordingReporter]) match {
        case Some(recordingRep : EventRecordingReporter) =>
          assert(recordingRep.testSucceededEventsReceived.size === 1)
          assert(recordingRep.suiteCompletedEventsReceived.size === 0)
          assert(recordingRep.suiteAbortedEventsReceived.size === 1)
        case _ => fail("Expected to find EventRecordingReporter, but not found.")
      }
    }
  }
  
  test("-W should cause AlertProvided to be fired", Flicker) {
    val runner = framework.runner(Array("-W", "1", "1", "-C", classOf[EventRecordingReporter].getName), Array.empty, testClassLoader)
    makeSureDone(runner) {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SlowSampleSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      assert(testEventHandler.successEventsReceived.size === 1)
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      scalatestRunner.done()
      scalatestRunner.dispatchReporter.reporters.find(_.isInstanceOf[EventRecordingReporter]) match {
        case Some(recordingRep : EventRecordingReporter) => 
          assert(recordingRep.testSucceededEventsReceived.size === 1)
          assert(recordingRep.alertProvidedEventsReceived.size > 0)
        case _ => fail("Expected to find EventRecordingReporter, but not found.")
      }
    }
  }

  test("Framework should work correctly with fork mode", Retryable) {
    val mainRunner = framework.runner(Array("-C", classOf[EventRecordingReporter].getName), Array.empty, testClassLoader)
    val remoteArgs = mainRunner.remoteArgs()
    val subRunner = framework.runner(Array("-C", classOf[EventRecordingReporter].getName), remoteArgs, testClassLoader)
    makeSureDone(mainRunner, subRunner) {
        val testEventHandler = new TestEventHandler
    
        val tasks = subRunner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector))))
        assert(tasks.size === 1)
        val task = tasks(0)
        task.execute(testEventHandler, Array(new TestLogger))
        val successEvents = testEventHandler.successEventsReceived
        assert(successEvents.length === 3)
        assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SampleSuite", "test 1", subclassFingerprint)
        assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SampleSuite", "test 2", subclassFingerprint)
        assertSuiteSuccessEvent(successEvents(2), "org.scalatest.tools.scalasbt.SampleSuite", "test 3", subclassFingerprint)
        assert(testEventHandler.errorEventsReceived.length === 0)
        assert(testEventHandler.failureEventsReceived.length === 0)
        assert(testEventHandler.skippedEventsReceived.length === 0)
        assert(mainRunner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
        val mainScalatestRunner = mainRunner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
        assert(subRunner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
        val subScalatestRunner = subRunner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
        subScalatestRunner.done()
        mainScalatestRunner.done()
        mainScalatestRunner.dispatchReporter.reporters.find(_.isInstanceOf[EventRecordingReporter]) match {
          case Some(recordingRep : EventRecordingReporter) => 
            assert(recordingRep.testSucceededEventsReceived.size === 3)
            assert(recordingRep.alertProvidedEventsReceived.size === 1)
            assert(recordingRep.noteProvidedEventsReceived.size === 1)
          case _ => fail("Expected to find EventRecordingReporter, but not found.")
        }
    }
  }

  test("SuitedAbored fired from nested suite should be reported as error correctly") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler

      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.AbortedSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val nestedTasks = task.execute(testEventHandler, Array(new TestLogger))
      assert(nestedTasks.size == 0)

      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 0)

      val failureEvents = testEventHandler.failureEventsReceived
      assert(failureEvents.length === 0)

      val errorEvents = testEventHandler.errorEventsReceived
      assert(errorEvents.length === 1)

      val skippedEvents = testEventHandler.skippedEventsReceived
      assert(skippedEvents.length === 0)

      val ignoredEvents = testEventHandler.ignoredEventsReceived
      assert(ignoredEvents.length === 0)

      val pendingEvents = testEventHandler.pendingEventsReceived
      assert(pendingEvents.length === 0)

      val canceledEvents = testEventHandler.canceledEventsReceived
      assert(canceledEvents.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("Nested suite should use passed-in args.configMap") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler

      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.NestedConfigMapSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val nestedTasks = task.execute(testEventHandler, Array(new TestLogger))
      assert(nestedTasks.size == 0)

      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 1)

      val failureEvents = testEventHandler.failureEventsReceived
      assert(failureEvents.length === 0)

      val errorEvents = testEventHandler.errorEventsReceived
      assert(errorEvents.length === 0)

      val skippedEvents = testEventHandler.skippedEventsReceived
      assert(skippedEvents.length === 0)

      val ignoredEvents = testEventHandler.ignoredEventsReceived
      assert(ignoredEvents.length === 0)

      val pendingEvents = testEventHandler.pendingEventsReceived
      assert(pendingEvents.length === 0)

      val canceledEvents = testEventHandler.canceledEventsReceived
      assert(canceledEvents.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("should accept -z and added it as TestWildcardSelector, which then select and run selected test(s) using wildcard in suite, excluding nested suites") {
    val runner = framework.runner(Array("-z", "est 1", "-z", "st 3"), Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array.empty)))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SampleSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SampleSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("should accept -z and added it as TestWildcardSelector, which then select and run selected test(s) in suite when it is explicitly specified, even when the suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array("-z", "st 1", "-z", "est 3"), Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, true, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("should accept -z and added it as TestWildcardSelector, which then do not select and run selected test(s) in suite when it is not explicitly specified and the suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array("-z", "est 1", "-z", "t 3"), Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 0)
    }
    finally {
      runner.done()
    }
  }

  // #######

  test("should accept -t and added it as TestSelector, which then select and run selected test(s) in suite, excluding nested suites") {
    val runner = framework.runner(Array("-t", "test 1", "-t", "test 3"), Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array.empty)))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.SampleSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.SampleSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("should accept -t and added it as TestSelector, which then select and run selected test(s) in suite when it is explicitly specified, even when the suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array("-t", "test 1", "-t", "test 3"), Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, true, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      task.execute(testEventHandler, Array(new TestLogger))
      val successEvents = testEventHandler.successEventsReceived
      assert(successEvents.length === 2)
      assertSuiteSuccessEvent(successEvents(0), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 1", subclassFingerprint)
      assertSuiteSuccessEvent(successEvents(1), "org.scalatest.tools.scalasbt.DoNotDiscoverSuite", "test 3", subclassFingerprint)
      assert(testEventHandler.errorEventsReceived.length === 0)
      assert(testEventHandler.failureEventsReceived.length === 0)
      assert(testEventHandler.skippedEventsReceived.length === 0)
    }
    finally {
      runner.done()
    }
  }

  test("should accept -t and added it as TestSelector, which then do not select and run selected test(s) in suite when it is not explicitly specified and the suite is annotated with @DoNotDiscover") {
    val runner = framework.runner(Array("-t", "test 1", "-t", "test 3"), Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.DoNotDiscoverSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 0)
    }
    finally {
      runner.done()
    }
  }

  test("ScalaTestRunner.task should return task and its nested task that tags method returns collection that contains tag inherited from superclass") {
    val runner = framework.runner(Array.empty, Array.empty, testClassLoader)
    try {
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleInheritedSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val resultTags = task.tags
      assert(resultTags.contains("cpu"))

      val testEventHandler = new TestEventHandler
      val nestedTasks = task.execute(testEventHandler, Array(new TestLogger))
      assert(nestedTasks.size == 0)
    }
    finally {
      runner.done()
    }
  }

  test("Framework.runner should use passed in -o config in sub-process") {
    val mainRunner = framework.runner(Array("-oDF"), Array.empty, testClassLoader)
    makeSureDone(mainRunner) {
      val remoteArgs = mainRunner.remoteArgs
      val subRunner = framework.runner(Array("-oDF"), remoteArgs, testClassLoader)

      makeSureDone(subRunner) {
        assert(subRunner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
        val scalatestRunner = subRunner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
        assert(scalatestRunner.useSbtLogInfoReporter)
        assert(scalatestRunner.presentAllDurations)
        assert(scalatestRunner.presentInColor)
        assert(scalatestRunner.presentShortStackTraces)
        assert(scalatestRunner.presentFullStackTraces)
        assert(!scalatestRunner.presentUnformatted)
        assert(!scalatestRunner.presentReminder)
        assert(!scalatestRunner.presentReminderWithShortStackTraces)
        assert(!scalatestRunner.presentReminderWithFullStackTraces)
        assert(!scalatestRunner.presentReminderWithoutCanceledTests)
      }
    }
  }

  test("Framework.runner should use the first -o when multiple -o is passed in") {
    val runner = framework.runner(Array("-oW", "-oDF"), Array.empty, testClassLoader)
    makeSureDone(runner) {
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      assert(scalatestRunner.useSbtLogInfoReporter)
      assert(!scalatestRunner.presentAllDurations)
      assert(!scalatestRunner.presentInColor)
      assert(!scalatestRunner.presentShortStackTraces)
      assert(!scalatestRunner.presentFullStackTraces)
      assert(!scalatestRunner.presentUnformatted)
      assert(!scalatestRunner.presentReminder)
      assert(!scalatestRunner.presentReminderWithShortStackTraces)
      assert(!scalatestRunner.presentReminderWithFullStackTraces)
      assert(!scalatestRunner.presentReminderWithoutCanceledTests)
    }
  }

  test("Framework.runner should use the first -o when multiple -e is passed in") {
    val runner = framework.runner(Array("-eW", "-eDF"), Array.empty, testClassLoader)
    makeSureDone(runner) {
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      val repConfig = scalatestRunner.repConfig
      val configSet = repConfig.standardErrReporterConfiguration.value.configSet
      assert(configSet.size == 1)
      assert(configSet.head == PresentWithoutColor)
    }
  }

  test("Framework.runner should not print out test succeeded event when -oC is passed") {
    val runner = framework.runner(Array("-oC"), Array.empty, testClassLoader)
    try {
      val testEventHandler = new TestEventHandler

      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.SampleSuite", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      val logger = new TestLogger
      task.execute(testEventHandler, Array(logger))
      assert(logger.infoReceived.length == 3)
      assert(logger.infoReceived(0) == "SampleSuite:")
      assert(logger.infoReceived(1) == "  + This is an alert! ")
      assert(logger.infoReceived(2) == "  + This is an update! ")
    }
    finally {
      runner.done()
    }
  }

  test("Framework.runner should fire SuiteStarting and SuiteAborted when error raised during construction") {
    val runner = framework.runner(Array("-C", classOf[EventRecordingReporter].getName), Array.empty, testClassLoader)
    makeSureDone(runner) {
      val testEventHandler = new TestEventHandler
      val tasks = runner.tasks(Array(new TaskDef("org.scalatest.tools.scalasbt.AbortedSuite2", subclassFingerprint, false, Array(new SuiteSelector))))
      assert(tasks.size === 1)
      val task = tasks(0)
      intercept[VirtualMachineError] {
        task.execute(testEventHandler, Array(new TestLogger))
      }
      assert(runner.isInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner])
      val scalatestRunner = runner.asInstanceOf[org.scalatest.tools.Framework#ScalaTestRunner]
      scalatestRunner.done()
      scalatestRunner.dispatchReporter.reporters.find(_.isInstanceOf[EventRecordingReporter]) match {
        case Some(recordingRep : EventRecordingReporter) =>
          assert(recordingRep.suiteStartingEventsReceived.size == 1)
          assert(recordingRep.suiteAbortedEventsReceived.size == 1)
        case _ => fail("Expected to find EventRecordingReporter, but not found.")
      }
    }
  }
}
