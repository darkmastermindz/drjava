/*BEGIN_COPYRIGHT_BLOCK
 *
 * This file is part of DrJava.  Download the current version of this project from http://www.drjava.org/
 * or http://sourceforge.net/projects/drjava/
 *
 * DrJava Open Source License
 * 
 * Copyright (C) 2001-2005 JavaPLT group at Rice University (javaplt@rice.edu).  All rights reserved.
 *
 * Developed by:   Java Programming Languages Team, Rice University, http://www.cs.rice.edu/~javaplt/
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated 
 * documentation files (the "Software"), to deal with the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and 
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * 
 *     - Redistributions of source code must retain the above copyright notice, this list of conditions and the 
 *       following disclaimers.
 *     - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the 
 *       following disclaimers in the documentation and/or other materials provided with the distribution.
 *     - Neither the names of DrJava, the JavaPLT, Rice University, nor the names of its contributors may be used to 
 *       endorse or promote products derived from this Software without specific prior written permission.
 *     - Products derived from this software may not be called "DrJava" nor use the term "DrJava" as part of their 
 *       names without prior written permission from the JavaPLT group.  For permission, write to javaplt@rice.edu.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO 
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS 
 * WITH THE SOFTWARE.
 * 
 *END_COPYRIGHT_BLOCK*/

package edu.rice.cs.drjava.model.debug;

import java.util.Vector;
import edu.rice.cs.drjava.model.DocumentRegion;
import edu.rice.cs.drjava.model.OpenDefinitionsDocument;

import com.sun.jdi.request.StepRequest;

/** Interface for any debugger implementation to be used by DrJava.
 *
 *  @version $Id$
 */
public interface Debugger {
  public static final int STEP_INTO = StepRequest.STEP_INTO;
  public static final int STEP_OVER = StepRequest.STEP_OVER;
  public static final int STEP_OUT = StepRequest.STEP_OUT;

  /** Adds a listener to this Debugger.
   *  @param listener a listener that reacts on events generated by the Debugger
   */
  public void addListener(DebugListener listener);

  /** Removes a listener to this Debugger.
   *  @param listener listener to remove
   */
  public void removeListener(DebugListener listener);

  /** Returns whether the debugger can be used in this copy of DrJava. This does not indicate whether it is ready to be
   *  used, which is indicated by isReady().
   */
  public boolean isAvailable();

  /** Attaches the debugger to the Interactions JVM to prepare for debugging. */
  public void startup() throws DebugException;

  /** Disconnects the debugger from the Interactions JVM and cleans up any state. */
  public void shutdown();

  /** Returns whether the debugger is enabled. */
  public boolean isReady();
  
//  /** Suspends execution of the thread referenced by d */
//  public void suspend(DebugThreadData d) throws DebugException;

//  /** Suspends all the threads in the VM the debugger is attached to. */
//  public void suspendAll();

  /** Sets the current thread we are debugging to the thread referenced by d. */
  public void setCurrentThread(DebugThreadData d) throws DebugException;

  /** Resumes execution of the currently loaded document. */
  public void resume() throws DebugException;

  /** Resumes execution of the given thread.
   *  @param data the DebugThreadData representing the thread to resume
   */
  public void resume(DebugThreadData data) throws DebugException;

  /** Steps into the execution of the currently loaded document.
   *  @param flag The flag denotes what kind of step to take. The following mark valid options:
   *         StepRequest.STEP_INTO
   *         StepRequest.STEP_OVER
   *         StepRequest.STEP_OUT
   */
  public void step(int flag) throws DebugException;

  /** Adds a watch on the given field or variable.
   *  @param field the name of the field we will watch
   */
  public void addWatch(String field) throws DebugException;

  /** Removes any watches on the given field or variable.
   *  @param field the name of the field we will watch
   */
  public void removeWatch(String field) throws DebugException;

  /** Removes the watch at the given index.
   *  @param index Index of the watch to remove
   */
  public void removeWatch(int index) throws DebugException;

  /** Removes all watches on existing fields and variables. */
  public void removeAllWatches() throws DebugException;


  /** Toggles whether a breakpoint is set at the given line in the given document.
   *  @param doc Document in which to set or remove the breakpoint
   *  @param offset Start offset on the line to set the breakpoint
   *  @param lineNum Line on which to set or remove the breakpoint
   *  @param enabled true if this breakpoint should be enabled
   */
  public void toggleBreakpoint(OpenDefinitionsDocument doc, int offset, int lineNum, boolean isEnabled) throws DebugException;

  /** Sets a breakpoint.
   *  @param breakpoint The new breakpoint to set
   */
  public void setBreakpoint(final Breakpoint breakpoint) throws DebugException;

 /** Removes a breakpoint. Called from ToggleBreakpoint -- even with BPs that are not active.
  *  @param breakpoint The breakpoint to remove.
  */
  public void removeBreakpoint(final Breakpoint breakpoint) throws DebugException;

  /** Returns all currently watched fields and variables. */
  public Vector<DebugWatchData> getWatches() throws DebugException;

  /** Returns a Vector of ThreadData. */
  public Vector<DebugThreadData> getCurrentThreadData() throws DebugException;

  /** Returns a Vector of StackData for the current thread. */
  public Vector<DebugStackData> getCurrentStackFrameData() throws DebugException;

  /**
   * @return true if there are any threads in the program currently being
   * debugged which have been suspended (by the user or by hitting a breakpoint).
   */
  public boolean hasSuspendedThreads() throws DebugException;

  /**
   * Returns whether the thread the debugger is tracking is now running.
   */
  public boolean hasRunningThread() throws DebugException;

  /**
   * Returns whether the debugger's current thread is suspended.
   */
  public boolean isCurrentThreadSuspended() throws DebugException;

  /**
   * scrolls to the source indicated by the given DebugStackData
   * @param data the DebugStackData representing the source location
   */
  public void scrollToSource(DebugStackData data) throws DebugException;

  /**
   * scrolls to the source indicated by the given Breakpoint
   * @param bp the Breakpoint representing the source location
   */
  public void scrollToSource(Breakpoint bp);

  /**
   * Gets the Breakpoint object at the specified line in the given class.
   * If the given data do not correspond to an actual breakpoint, null is returned.
   * @param line the line number of the breakpoint
   * @param className the name of the class the breakpoint's in
   * @return the Breakpoint corresponding to the line and className, or null if
   *         there is no such breakpoint.
   */
  public Breakpoint getBreakpoint(int line, String className) throws DebugException;
}
