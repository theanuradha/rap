/*******************************************************************************
 * Copyright (c) 2008, 2009 Innoopract Informationssysteme GmbH.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Innoopract Informationssysteme GmbH - initial API and implementation
 *     EclipseSource - ongoing development
 ******************************************************************************/
package org.eclipse.rwt.internal.lifecycle;

import org.eclipse.rwt.internal.service.*;
import org.eclipse.rwt.lifecycle.PhaseId;
import org.eclipse.rwt.service.ISessionStore;
import org.eclipse.swt.widgets.Display;


final class UIThread
  extends Thread
  implements IUIThreadHolder, ISessionShutdownAdapter
{

  private ServiceContext serviceContext;
  private ISessionStore sessionStore;
  private Runnable shutdownCallback;
  private boolean uiThreadTerminating;

  public UIThread( final Runnable runnable ) {
    super( runnable );
  }

  //////////////////////////
  // interface IThreadHolder

  public void setServiceContext( final ServiceContext serviceContext ) {
    this.serviceContext = serviceContext;
  }

  public void updateServiceContext() {
    if( ContextProvider.hasContext() ) {
      ContextProvider.releaseContextHolder();
    }
    ContextProvider.setContext( serviceContext );
  }

  public void switchThread() throws InterruptedException {
    Object lock = getLock();
    synchronized( lock ) {
      // [rh] While working on bug 284202, there was the suspicion that a
      // request thread might wait infinitley on an already terminated UIThread.
      // To investigate this problem, we print to sys-err if this happens.
      if( !getThread().isAlive() ) {
        String msg
          = "Thread '"
          + Thread.currentThread()
          + "' is waiting for already terminated UIThread";
        Exception e = new RuntimeException( msg );
        ServletLog.log( "", e );
      }
      lock.notifyAll();
      try {
        lock.wait();
      } catch( InterruptedException e ) {
        if( uiThreadTerminating ) {
          // Equip the UI thread that is continuing its execution with a 
          // service context and the proper phase (see terminateThread).
          updateServiceContext();
          CurrentPhase.set( PhaseId.PROCESS_ACTION );
          uiThreadTerminating = false;
        }
        throw e;
      }
    }
  }

  public void run() {
    try {
      super.run();
    } finally {
      // TODO [rh] call lock.notifyAll()?
    }
  }

  public void terminateThread() {
    // Prepare a service context to be used by the UI thread that may continue
    // to run as a result of the interrupt call
    ServiceContext serviceContext = createServiceContext();
    setServiceContext( serviceContext );
    uiThreadTerminating = true;
    // interrupt the UI thread that is expected to wait in switchThread or
    // already be terminated
    getThread().interrupt();
    try {
      getThread().join();
    } catch( InterruptedException e ) {
      String msg = "Received InterruptedException while terminating UIThread";
      ServletLog.log( msg, e );
    }
    uiThreadTerminating = false;
  }

  public Thread getThread() {
    return this;
  }

  public Object getLock() {
    // TODO [rh] use a distinct (final) lock object instead of 'this'
    return this;
  }


  ////////////////////////////////////
  // interface ISessionShutdownAdapter

  public void setSessionStore( final ISessionStore sessionStore ) {
    this.sessionStore = sessionStore;
  }

  public void setShutdownCallback( final Runnable shutdownCallback ) {
    this.shutdownCallback = shutdownCallback;
  }

  public void interceptShutdown() {
    terminateThread();
  }

  public void processShutdown() {
    updateServiceContext();
    try {
      // Simulate PROCESS_ACTION phase if the session times out
      CurrentPhase.set( PhaseId.PROCESS_ACTION );
      // TODO [rh] find a better decoupled way to dispose of the display
      Display display = RWTLifeCycle.getSessionDisplay();
      if( display != null ) {
        display.dispose();
      }
      shutdownCallback.run();
    } finally {
      ContextProvider.disposeContext();
    }
  }

  //////////////////
  // Helping methods

  private ServiceContext createServiceContext() {
    ServiceContext result
      = UICallBackServiceHandler.getFakeContext( sessionStore );
    result.setStateInfo( new ServiceStateInfo() );
    return result;
  }
}
