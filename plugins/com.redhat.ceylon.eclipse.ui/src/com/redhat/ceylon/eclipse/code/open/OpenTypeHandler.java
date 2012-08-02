package com.redhat.ceylon.eclipse.code.open;

import static com.redhat.ceylon.eclipse.code.editor.Util.getCurrentEditor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

public class OpenTypeHandler extends AbstractHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        new OpenTypeAction(getCurrentEditor()).run();
        return null;
    }
        
}