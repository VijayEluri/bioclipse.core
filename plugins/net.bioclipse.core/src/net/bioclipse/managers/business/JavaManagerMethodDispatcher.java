package net.bioclipse.managers.business;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.bioclipse.core.IResourcePathTransformer;
import net.bioclipse.core.ResourcePathTransformer;
import net.bioclipse.core.business.BioclipseException;
import net.bioclipse.core.util.LogUtils;
import net.bioclipse.jobs.BioclipseJob;
import net.bioclipse.jobs.BioclipseJobUpdateHook;
import net.bioclipse.jobs.BioclipseUIJob;
import net.bioclipse.jobs.ExtendedBioclipseJob;
import net.bioclipse.jobs.IReturner;
import net.bioclipse.managers.business.AbstractManagerMethodDispatcher.ReturnCollector;

import org.aopalliance.intercept.MethodInvocation;
import org.apache.log4j.Logger;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceRuleFactory;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.MultiRule;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.swt.widgets.Display;

/**
 * @author jonalv
 *
 */
public class JavaManagerMethodDispatcher 
       extends AbstractManagerMethodDispatcher {

    IResourcePathTransformer transformer 
        = ResourcePathTransformer.getInstance();
    private Logger logger 
        = Logger.getLogger( JavaManagerMethodDispatcher.class );
    
    @Override
    public Object doInvoke( IBioclipseManager manager, 
                            Method method,
                            Object[] arguments,
                            MethodInvocation invocation,
                            boolean notExtended) 
                  throws BioclipseException {

        if ( Arrays.asList( method.getParameterTypes() )
                   .contains( IProgressMonitor.class ) &&
             ( invocation.getMethod()
                         .getReturnType() == void.class || 
               invocation.getMethod()
                         .getReturnType() == BioclipseJob.class ||
               invocation.getMethod()
                         .getReturnType() == ExtendedBioclipseJob.class ) ) {
            
            return runAsJob(manager, method, arguments, invocation, notExtended);
        }
        
        return runInSameThread(manager, method, arguments);
    }

    private Object runInSameThread( IBioclipseManager manager, Method method,
                                    Object[] arguments ) 
                   throws BioclipseException {

        //translate String -> IFile
        for ( int i = 0; i < arguments.length; i++ ) {
            if ( arguments[i] instanceof String &&
                 method.getParameterTypes()[i] == IFile.class ) {
                arguments[i] = transformer.transform( (String)arguments[i] );
            }
        }
        
        List<Object> args = new ArrayList<Object>( Arrays.asList( arguments ) );
        
        boolean doingPartialReturns = false;
        ReturnCollector returnCollector = new ReturnCollector();
        
        //add partial returner if needed
        for ( Class<?> param : method.getParameterTypes() ) {
            if ( param.isAssignableFrom( IReturner.class ) ) {
                doingPartialReturns = true;
                args.add( returnCollector );
            }
        }
        
        //remove partial returner if needed
        if ( !doingPartialReturns ) {
            int toBeremoved = -1;
            for ( int i = 0; i < arguments.length; i++ ) {
                if ( args.get(i) instanceof IReturner ) {
                    toBeremoved = i;
                }
            }
            if ( toBeremoved != -1 ) {
                args.remove( toBeremoved );
            }
        }
        
        //Add a NullProgressMonitor if needed
        if ( Arrays.asList( method.getParameterTypes() )
                   .contains( IProgressMonitor.class ) &&
             !Arrays.asList( arguments ).contains( IProgressMonitor.class ) ) {
            
            args.add( new NullProgressMonitor() );
        }
        
        //Remove BioclipseUiJob if there
        BioclipseUIJob<Object> uiJob = null;
        for ( Object o : args ) {
            if ( o instanceof BioclipseUIJob ) {
                uiJob = (BioclipseUIJob<Object>) o;
            }
        }
        if ( uiJob != null ) {
            args.remove( uiJob );
        }

        arguments = args.toArray();
        Object returnValue = null;
        try {
            if ( doingPartialReturns ) {
                method.invoke( manager, arguments );
                returnValue = returnCollector.getReturnValue();
                if ( returnValue == null ) {
                    returnValue = returnCollector.getReturnValues();
                }
            }
            else {
                returnValue = method.invoke( manager, arguments );
            }
        } 
        catch ( Exception e ) {
            Throwable t = e;
            while ( t.getCause() != null ) {
                if ( t.getCause() instanceof BioclipseException) {
                    throw (BioclipseException)t.getCause();
                }
                t = t.getCause();
            }
            throw new RuntimeException (
                "Failed to run method " + manager.getManagerName() 
                + "." + method.getName(), 
                e);
        }
        if ( uiJob != null ) {
            final BioclipseUIJob<Object> finalUiJob = uiJob;
            finalUiJob.setReturnValue( returnValue );
            Display.getDefault().asyncExec( new Runnable() {
                public void run() {
                    finalUiJob.runInUI();
                }
            });
        }
        return returnValue;
    }

    private Object runAsJob( IBioclipseManager manager, 
                             Method method,
                             Object[] arguments, 
                             MethodInvocation invocation, 
                             boolean notExtended ) {

        //find update hook
        BioclipseJobUpdateHook hook = null;
        for ( Object argument : arguments ) {
            if ( argument instanceof BioclipseJobUpdateHook ) {
                hook = (BioclipseJobUpdateHook) argument;
            }
        }
        BioclipseJob<?> job;
        if ( notExtended ) {
            job = new BioclipseJob( hook != null ? hook.getJobName()
                                                 : manager.getManagerName() 
                                                   + "." + method.getName() );
        }
        else {
            job = new ExtendedBioclipseJob( 
                          hook != null ? hook.getJobName()
                                       : manager.getManagerName() + "." 
                                         + method.getName() );
        }
        
        job.setMethod( method );
        job.setArguments( arguments );
        job.setMethodCalled( invocation.getMethod() );
        job.setBioclipseManager( manager );
               
        job.setUser( false );
        if ( notExtended ) {
            job.schedule();
        }
        return job;
    }

    @Override
    protected Object doInvokeInGuiThread( final IBioclipseManager manager, 
                                          final Method method,
                                          final Object[] arguments,
                                          final MethodInvocation invocation ) {

        //translate String -> IFile
        for ( int i = 0; i < arguments.length; i++ ) {
            if ( arguments[i] instanceof String &&
                 method.getParameterTypes()[i] == IFile.class ) {
                arguments[i] = transformer.transform( (String)arguments[i] );
            }
        }
        Display.getDefault().asyncExec( new Runnable() {

            public void run() {
                try {
                    method.invoke( manager, arguments );
                } catch ( Exception e ) {
                    Throwable root = LogUtils.findRootOrBioclipseException( e ); 
                    LogUtils.handleException( 
                        root, 
                        logger, 
                        "net.bioclipse.managers",
                        new Status( IStatus.ERROR, 
                                    "net.bioclipse.managers", 
                                    root.getClass().getSimpleName() + ": " 
                                      + root.getMessage() + "\n"
                                      + "Exception occured while running " 
                                      + "manager method: " 
                                      + manager.getManagerName() + "." 
                                      + method.getName(), 
                                    root) );
                }
            }
        });
        return null;
    }

    @Override
    protected Object doInvokeInSameThread( IBioclipseManager manager, 
                                           Method method,
                                           Object[] arguments,
                                           MethodInvocation invocation)
                     throws BioclipseException {

        return super.doInvoke(manager, method, arguments, invocation, true);
    }
}
