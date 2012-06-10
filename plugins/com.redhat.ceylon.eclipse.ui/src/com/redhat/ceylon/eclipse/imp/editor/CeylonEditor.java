package com.redhat.ceylon.eclipse.imp.editor;

import static org.eclipse.imp.editor.IEditorActionDefinitionIds.SHOW_OUTLINE;
import static org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.EDITOR_SPACES_FOR_TABS;
import static org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.EDITOR_TAB_WIDTH;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.BreakIterator;
import java.text.CharacterIterator;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.debug.ui.actions.IToggleBreakpointsTarget;
import org.eclipse.imp.editor.GenerateActionGroup;
import org.eclipse.imp.editor.OpenEditorActionGroup;
import org.eclipse.imp.editor.OutlineInformationControl;
import org.eclipse.imp.editor.OutlineLabelProvider;
import org.eclipse.imp.editor.ParserScheduler;
import org.eclipse.imp.editor.StructuredSourceViewerConfiguration;
import org.eclipse.imp.editor.UniversalEditor;
import org.eclipse.imp.language.Language;
import org.eclipse.imp.parser.IMessageHandler;
import org.eclipse.imp.parser.IParseController;
import org.eclipse.imp.services.base.TreeModelBuilderBase;
import org.eclipse.imp.ui.DefaultPartListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.AbstractInformationControlManager;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.information.IInformationPresenter;
import org.eclipse.jface.text.information.IInformationProvider;
import org.eclipse.jface.text.information.IInformationProviderExtension;
import org.eclipse.jface.text.information.InformationPresenter;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.jface.text.link.LinkedPosition;
import org.eclipse.jface.text.quickassist.IQuickAssistAssistant;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.IUpdate;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;
import org.eclipse.ui.texteditor.TextNavigationAction;
import org.eclipse.ui.themes.ITheme;

import com.redhat.ceylon.eclipse.debug.ui.actions.ToggleBreakpointAdapter;
import com.redhat.ceylon.eclipse.imp.core.JavaReferenceResolver;
import com.redhat.ceylon.eclipse.imp.outline.CeylonLabelDecorator;
import com.redhat.ceylon.eclipse.imp.outline.CeylonTreeModelBuilder;
import com.redhat.ceylon.eclipse.imp.parser.CeylonParseController;
import com.redhat.ceylon.eclipse.imp.quickfix.CeylonQuickFixController;

public class CeylonEditor extends UniversalEditor {
    private static final String TEXT_FONT_PREFERENCE = "com.redhat.ceylon.eclipse.ui.editorFont";
    
    private static Field refreshContributionsField;
    private static Field generateActionGroupField;
    private static Field openEditorActionGroupField;
    private static Field labelProviderField;
    private static Field fParserSchedulerField;
    private static Method updateCaretMethod;
    private static Field fAnnotationCreatorField;
    private static Field fDocumentListenerField;
    private static Method watchDocumentMethod;

    static {
        try {
            refreshContributionsField = UniversalEditor.class.getDeclaredField("fRefreshContributions");
            refreshContributionsField.setAccessible(true);
            generateActionGroupField = UniversalEditor.class.getDeclaredField("fGenerateActionGroup");
            generateActionGroupField.setAccessible(true);
            openEditorActionGroupField = UniversalEditor.class.getDeclaredField("fOpenEditorActionGroup");
            openEditorActionGroupField.setAccessible(true);
            labelProviderField = OutlineInformationControl.class.getDeclaredField("fInnerLabelProvider");
            labelProviderField.setAccessible(true);
            fParserSchedulerField = UniversalEditor.class.getDeclaredField("fParserScheduler");
            fParserSchedulerField.setAccessible(true);
            fAnnotationCreatorField = UniversalEditor.class.getDeclaredField("fAnnotationCreator");
            fAnnotationCreatorField.setAccessible(true);
            updateCaretMethod = AbstractTextEditor.class.getDeclaredMethod("updateCaret");
            updateCaretMethod.setAccessible(true);
            
            fDocumentListenerField = UniversalEditor.class.getDeclaredField("fDocumentListener");
            fDocumentListenerField.setAccessible(true);
            watchDocumentMethod = UniversalEditor.class.getDeclaredMethod("watchDocument", new Class[] {Long.TYPE});
            watchDocumentMethod.setAccessible(true);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateFontAndCaret() {
        Font font = currentTheme.getFontRegistry().get(TEXT_FONT_PREFERENCE);
        getSourceViewer().getTextWidget().setFont(font);
        try {
            updateCaretMethod.invoke(this);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static final TreeModelBuilderBase builder = new CeylonTreeModelBuilder();

    private static final class CeylonResourceChangeListener implements
            IResourceChangeListener {
        CeylonEditor editor;
        CeylonResourceChangeListener(CeylonEditor editor) {
            this.editor=editor;
        }
        public void resourceChanged(IResourceChangeEvent event) {
            if (event.getType() != IResourceChangeEvent.POST_BUILD)
                return;
            if (event.getBuildKind() != IncrementalProjectBuilder.AUTO_BUILD)
                return;
            
            IParseController pc= editor.getParseController();
            if (pc == null) {
                return;
            }
            IPath oldWSRelPath= pc.getProject().getRawProject().getFullPath().append(pc.getPath());
            IResourceDelta rd= event.getDelta().findMember(oldWSRelPath);

            if (rd != null) {
                editor.scheduleParsing();
            }
        }
    }

    private static final class CeylonInformationControlCreator implements
            IInformationControlCreator {
        private final Language lang;
        
        private CeylonInformationControlCreator(Language lang) {
            this.lang = lang;
        }

        @Override
        public IInformationControl createInformationControl(Shell parent) {
            return new OutlineInformationControl(parent, SWT.RESIZE, SWT.V_SCROLL | SWT.H_SCROLL, SHOW_OUTLINE, lang) {
                @Override
                protected TreeViewer createTreeViewer(Composite parent, int style) {
                    TreeViewer tv = super.createTreeViewer(parent, style);
                    try {
                        OutlineLabelProvider lp = (OutlineLabelProvider) labelProviderField.get(this);
                        lp.addLabelDecorator(new CeylonLabelDecorator(lang));
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                    return tv;
                }
                @Override
                protected String getStatusFieldText() {
                    return "";
                }
            };
        }
    }

    private static final class CeylonStructuredSourceViewerConfiguration 
            extends StructuredSourceViewerConfiguration 
            implements IInformationProvider, IInformationProviderExtension {
        private CeylonStructuredSourceViewerConfiguration(IPreferenceStore prefStore, UniversalEditor editor) {
            super(prefStore, editor);
        }

        @Override
        public IQuickAssistAssistant getQuickAssistAssistant(
                ISourceViewer sourceViewer) {
            return new CeylonQuickFixController(fEditor);
        }
        
        @Override
        public IInformationPresenter getOutlinePresenter(ISourceViewer sourceViewer) {
            InformationPresenter presenter = new InformationPresenter(new CeylonInformationControlCreator(fEditor.getLanguage()));
            presenter.setDocumentPartitioning(getConfiguredDocumentPartitioning(sourceViewer));
            presenter.setAnchor(AbstractInformationControlManager.ANCHOR_GLOBAL);
            presenter.setInformationProvider(this, IDocument.DEFAULT_CONTENT_TYPE);
            // TODO Should associate all other partition types with this provider, too
            //presenter.setSizeConstraints(50, 20, true, false);
            //presenter.setRestoreInformationControlBounds(getSettings("outline_presenter_bounds"), true, true);
            return presenter;
        }

        public IRegion getSubject(ITextViewer textViewer, int offset) {
            return new Region(offset, 0); // Could be anything, since it's ignored below in getInformation2()...
        }
        public String getInformation(ITextViewer textViewer, IRegion subject) {
            // shouldn't be called, given IInformationProviderExtension???
            throw new UnsupportedOperationException();
        }
        public Object getInformation2(ITextViewer textViewer, IRegion subject) {
            return builder.buildTree(getLanguageServiceManager().getParseController().getCurrentAst());
        }

        @Override
        public int getTabWidth(ISourceViewer sourceViewer) {
            return ((CeylonEditor)fEditor).getPreferenceStore().getInt(EDITOR_TAB_WIDTH);
        }
        
        @Override
        public IHyperlinkDetector[] getHyperlinkDetectors(
                ISourceViewer sourceViewer) {
            IHyperlinkDetector[] detectors = super.getHyperlinkDetectors(sourceViewer);
            IHyperlinkDetector[] result = new IHyperlinkDetector[detectors.length+1];
            for (int i=0; i<detectors.length; i++) {
                result[i]=detectors[i];
            }
            result[detectors.length] = new JavaReferenceResolver((CeylonEditor)fEditor);
            return result;
        }
    }

    private IResourceChangeListener fResourceListener;

    private IPropertyChangeListener colorChangeListener = new IPropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent event) {
            if (event.getProperty().startsWith("com.redhat.ceylon.eclipse.ui.theme.color.")) {
                getSourceViewer().invalidateTextPresentation();
            }
        }
    };
    
    IPropertyChangeListener fontChangeListener = new IPropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent event) {
            if (event.getProperty().equals(TEXT_FONT_PREFERENCE)) {
                updateFontAndCaret();
            }
        }
    };
    private final ITheme currentTheme = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme();
    
    @Override
    protected StructuredSourceViewerConfiguration createSourceViewerConfiguration() {
        return new CeylonStructuredSourceViewerConfiguration(getPreferenceStore(), this);
    }
        
    /*private IDialogSettings getSettings(String sectionName) {
        IDialogSettings settings= RuntimePlugin.getInstance().getDialogSettings().getSection(sectionName);
        if (settings == null)
            settings= RuntimePlugin.getInstance().getDialogSettings().addNewSection(sectionName);
        return settings;
    }*/

    @Override
    public void createPartControl(Composite parent) {
        
        String esft = getPreferenceStore().getString(EDITOR_SPACES_FOR_TABS);
        //String etw = getPreferenceStore().getString(EDITOR_TAB_WIDTH);
        super.createPartControl(parent);
        getPreferenceStore().setValue(EDITOR_SPACES_FOR_TABS, esft);
        //getPreferenceStore().setValue(EDITOR_TAB_WIDTH, etw);

        CeylonParserScheduler ceylonParserScheduler;
        try {
            Field astListenersField = ParserScheduler.class.getDeclaredField("fAstListeners");
            astListenersField.setAccessible(true);

            ParserScheduler parserScheduler = (ParserScheduler) fParserSchedulerField.get(this);
            Object astListeners = astListenersField.get(parserScheduler);
            ceylonParserScheduler = new CeylonParserScheduler(getParseController(), this, getDocumentProvider(),
                    (IMessageHandler) fAnnotationCreatorField.get(this));
            astListenersField.set(ceylonParserScheduler, astListeners);
            fParserSchedulerField.set(this, ceylonParserScheduler);
/*
            IDocumentListener docLister = (IDocumentListener) fDocumentListenerField.get(this);
            getDocumentProvider().getDocument(getEditorInput()).removeDocumentListener(docLister);
            watchDocumentMethod.invoke(this, new Object[] {100});
            */
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        try {
            getSite().getPage().removePartListener((DefaultPartListener) refreshContributionsField.get(this));
            generateActionGroupField.set(this, new CeylonGenerateActionGroup(this));
            openEditorActionGroupField.set(this, new CeylonOpenEditorActionGroup(this));
            watchForSourceBuild();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        getSite().getPage().hideActionSet(IMP_CODING_ACTION_SET);
        getSite().getPage().hideActionSet(IMP_OPEN_ACTION_SET);
        ((IContextService) getSite().getService(IContextService.class))
            .activateContext("com.redhat.ceylon.eclipse.ui.context");
        
        //CeylonPlugin.getInstance().getPreferenceStore().addPropertyChangeListener(colorChangeListener);
        currentTheme.getColorRegistry().addListener(colorChangeListener);
        updateFontAndCaret();
        currentTheme.getFontRegistry().addListener(fontChangeListener);
        
    }
    
    @Override
    protected void configureSourceViewerDecorationSupport(
            SourceViewerDecorationSupport support) {
        setupMatchingBrackets();
        super.configureSourceViewerDecorationSupport(support);
    }

    private void setupMatchingBrackets() {
        IPreferenceStore store = getPreferenceStore();
        store.setDefault(MATCHING_BRACKETS, true);
        Color color = currentTheme.getColorRegistry()
                    .get("com.redhat.ceylon.eclipse.ui.theme.matchingBracketsColor");
        store.setDefault(MATCHING_BRACKETS_COLOR, 
                color.getRed() +"," + color.getGreen() + "," + color.getBlue());
    }
    
    private void watchForSourceBuild() {
        if (fLanguageServiceManager == null ||
            fLanguageServiceManager.getParseController() == null ||
            fLanguageServiceManager.getParseController().getProject() == null) {
            return;
        }
        
        fResourceListener= new CeylonResourceChangeListener(this);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(fResourceListener, 
                IResourceChangeEvent.POST_BUILD);
    }
    
    public void scheduleParsing() {
        try {
            ParserScheduler scheduler = (ParserScheduler) fParserSchedulerField.get(CeylonEditor.this);
            if (scheduler != null) {
                scheduler.setPriority(ParserScheduler.INTERACTIVE);
                scheduler.cancel();
                scheduler.schedule(10);
            }
        } catch (IllegalArgumentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    static class CeylonGenerateActionGroup extends GenerateActionGroup {
        public CeylonGenerateActionGroup(UniversalEditor editor) {
            super(editor, "");
        }
        @Override
        public void fillContextMenu(IMenuManager menu) {}
    }
    
    static class CeylonOpenEditorActionGroup extends OpenEditorActionGroup {
        public CeylonOpenEditorActionGroup(UniversalEditor editor) {
            super(editor);
        }
        @Override
        public void fillContextMenu(IMenuManager menu) {}
    }
    
    private SourceArchiveDocumentProvider sourceArchiveDocumentProvider;
    private Object toggleBreakpointTarget;
    
    @Override
    public IDocumentProvider getDocumentProvider() {
        if (SourceArchiveDocumentProvider.canHandle(getEditorInput())) {
            if (sourceArchiveDocumentProvider == null) {
                sourceArchiveDocumentProvider= new SourceArchiveDocumentProvider();
            }
            return sourceArchiveDocumentProvider;
        }
        return super.getDocumentProvider();
    }

    public CeylonParseController getParseController() {
        return (CeylonParseController) super.getParseController();
    }
    
    @Override
    protected void createNavigationActions() {
        super.createNavigationActions();
        
        final StyledText textWidget= getSourceViewer().getTextWidget();

        /*IAction action= new SmartLineStartAction(textWidget, false);
        action.setActionDefinitionId(ITextEditorActionDefinitionIds.LINE_START);
        editor.setAction(ITextEditorActionDefinitionIds.LINE_START, action);

        action= new SmartLineStartAction(textWidget, true);
        action.setActionDefinitionId(ITextEditorActionDefinitionIds.SELECT_LINE_START);
        editor.setAction(ITextEditorActionDefinitionIds.SELECT_LINE_START, action);*/

        setAction(ITextEditorActionDefinitionIds.WORD_PREVIOUS, 
                new NavigatePreviousSubWordAction());
        textWidget.setKeyBinding(SWT.CTRL | SWT.ARROW_LEFT, SWT.NULL);

        setAction(ITextEditorActionDefinitionIds.WORD_NEXT, 
                new NavigateNextSubWordAction());
        textWidget.setKeyBinding(SWT.CTRL | SWT.ARROW_RIGHT, SWT.NULL);

        setAction(ITextEditorActionDefinitionIds.SELECT_WORD_PREVIOUS, 
                new SelectPreviousSubWordAction());
        textWidget.setKeyBinding(SWT.CTRL | SWT.SHIFT | SWT.ARROW_LEFT, SWT.NULL);

        setAction(ITextEditorActionDefinitionIds.SELECT_WORD_NEXT, 
                new SelectNextSubWordAction());
        textWidget.setKeyBinding(SWT.CTRL | SWT.SHIFT | SWT.ARROW_RIGHT, SWT.NULL);

        setAction(ITextEditorActionDefinitionIds.DELETE_PREVIOUS_WORD, new DeletePreviousSubWordAction());
        textWidget.setKeyBinding(SWT.CTRL | SWT.BS, SWT.NULL);
        markAsStateDependentAction(ITextEditorActionDefinitionIds.DELETE_PREVIOUS_WORD, true);

        setAction(ITextEditorActionDefinitionIds.DELETE_NEXT_WORD, new DeleteNextSubWordAction());
        textWidget.setKeyBinding(SWT.CTRL | SWT.DEL, SWT.NULL);
        markAsStateDependentAction(ITextEditorActionDefinitionIds.DELETE_NEXT_WORD, true);
    }
    
    protected class NavigateNextSubWordAction extends NextSubWordAction {
        public NavigateNextSubWordAction() {
            super(ST.WORD_NEXT);
            setActionDefinitionId(ITextEditorActionDefinitionIds.WORD_NEXT);
        }
        @Override
        protected void setCaretPosition(final int position) {
            getTextWidget().setCaretOffset(modelOffset2WidgetOffset(getSourceViewer(), position));
        }
    }

    protected class NavigatePreviousSubWordAction extends PreviousSubWordAction {
        public NavigatePreviousSubWordAction() {
            super(ST.WORD_PREVIOUS);
            setActionDefinitionId(ITextEditorActionDefinitionIds.WORD_PREVIOUS);
        }
        @Override
        protected void setCaretPosition(final int position) {
            getTextWidget().setCaretOffset(modelOffset2WidgetOffset(getSourceViewer(), position));
        }
    }

    protected abstract class NextSubWordAction extends TextNavigationAction {

        protected CeylonWordIterator fIterator= new CeylonWordIterator();

        /**
         * Creates a new next sub-word action.
         *
         * @param code Action code for the default operation. Must be an action code from @see org.eclipse.swt.custom.ST.
         */
        protected NextSubWordAction(int code) {
            super(getSourceViewer().getTextWidget(), code);
        }

        /*
         * @see org.eclipse.jface.action.IAction#run()
         */
        @Override
        public void run() {
            // Check whether we are in a java code partition and the preference is enabled

            final ISourceViewer viewer= getSourceViewer();
            final IDocument document= viewer.getDocument();
            try {
                fIterator.setText((CharacterIterator)new DocumentCharacterIterator(document));
                int position= widgetOffset2ModelOffset(viewer, viewer.getTextWidget().getCaretOffset());
                if (position == -1)
                    return;

                int next= findNextPosition(position);
                if (next != BreakIterator.DONE) {
                    setCaretPosition(next);
                    getTextWidget().showSelection();
                    fireSelectionChanged();
                }
            } catch (BadLocationException x) {
                // ignore
            }
        }
        
        /**
         * Finds the next position after the given position.
         *
         * @param position the current position
         * @return the next position
         */
        protected int findNextPosition(int position) {
            ISourceViewer viewer= getSourceViewer();
            int widget= -1;
            int next= position;
            while (next != BreakIterator.DONE && widget == -1) { // XXX: optimize
                next= fIterator.following(next);
                if (next != BreakIterator.DONE)
                    widget= modelOffset2WidgetOffset(viewer, next);
            }

            IDocument document= viewer.getDocument();
            LinkedModeModel model= LinkedModeModel.getModel(document, position);
            if (model != null) {
                LinkedPosition linkedPosition= model.findPosition(new LinkedPosition(document, position, 0));
                if (linkedPosition != null) {
                    int linkedPositionEnd= linkedPosition.getOffset() + linkedPosition.getLength();
                    if (position != linkedPositionEnd && linkedPositionEnd < next)
                        next= linkedPositionEnd;
                } else {
                    LinkedPosition nextLinkedPosition= model.findPosition(new LinkedPosition(document, next, 0));
                    if (nextLinkedPosition != null) {
                        int nextLinkedPositionOffset= nextLinkedPosition.getOffset();
                        if (position != nextLinkedPositionOffset && nextLinkedPositionOffset < next)
                            next= nextLinkedPositionOffset;
                    }
                }
            }

            return next;
        }

        /**
         * Sets the caret position to the sub-word boundary given with <code>position</code>.
         *
         * @param position Position where the action should move the caret
         */
        protected abstract void setCaretPosition(int position);
    }

    protected abstract class PreviousSubWordAction extends TextNavigationAction {

        protected CeylonWordIterator fIterator= new CeylonWordIterator();

        /**
         * Creates a new previous sub-word action.
         *
         * @param code Action code for the default operation. Must be an action code from @see org.eclipse.swt.custom.ST.
         */
        protected PreviousSubWordAction(final int code) {
            super(getSourceViewer().getTextWidget(), code);
        }

        @Override
        public void run() {
            // Check whether we are in a java code partition and the preference is enabled

            final ISourceViewer viewer= getSourceViewer();
            final IDocument document= viewer.getDocument();
            try {
                fIterator.setText((CharacterIterator)new DocumentCharacterIterator(document));
                int position= widgetOffset2ModelOffset(viewer, viewer.getTextWidget().getCaretOffset());
                if (position == -1)
                    return;

                int previous= findPreviousPosition(position);
                if (previous != BreakIterator.DONE) {
                    setCaretPosition(previous);
                    getTextWidget().showSelection();
                    fireSelectionChanged();
                }
            } catch (BadLocationException x) {
                // ignore - getLineOfOffset failed
            }

        }

        /**
         * Finds the previous position before the given position.
         *
         * @param position the current position
         * @return the previous position
         */
        protected int findPreviousPosition(int position) {
            ISourceViewer viewer= getSourceViewer();
            int widget= -1;
            int previous= position;
            while (previous != BreakIterator.DONE && widget == -1) { // XXX: optimize
                previous= fIterator.preceding(previous);
                if (previous != BreakIterator.DONE)
                    widget= modelOffset2WidgetOffset(viewer, previous);
            }

            IDocument document= viewer.getDocument();
            LinkedModeModel model= LinkedModeModel.getModel(document, position);
            if (model != null) {
                LinkedPosition linkedPosition= model.findPosition(new LinkedPosition(document, position, 0));
                if (linkedPosition != null) {
                    int linkedPositionOffset= linkedPosition.getOffset();
                    if (position != linkedPositionOffset && previous < linkedPositionOffset)
                        previous= linkedPositionOffset;
                } else {
                    LinkedPosition previousLinkedPosition= model.findPosition(new LinkedPosition(document, previous, 0));
                    if (previousLinkedPosition != null) {
                        int previousLinkedPositionEnd= previousLinkedPosition.getOffset() + previousLinkedPosition.getLength();
                        if (position != previousLinkedPositionEnd && previous < previousLinkedPositionEnd)
                            previous= previousLinkedPositionEnd;
                    }
                }
            }

            return previous;
        }

        /**
         * Sets the caret position to the sub-word boundary given with <code>position</code>.
         *
         * @param position Position where the action should move the caret
         */
        protected abstract void setCaretPosition(int position);
    }
    
    protected class SelectNextSubWordAction extends NextSubWordAction {
        public SelectNextSubWordAction() {
            super(ST.SELECT_WORD_NEXT);
            setActionDefinitionId(ITextEditorActionDefinitionIds.SELECT_WORD_NEXT);
        }
        @Override
        protected void setCaretPosition(final int position) {
            final ISourceViewer viewer= getSourceViewer();

            final StyledText text= viewer.getTextWidget();
            if (text != null && !text.isDisposed()) {

                final Point selection= text.getSelection();
                final int caret= text.getCaretOffset();
                final int offset= modelOffset2WidgetOffset(viewer, position);

                if (caret == selection.x)
                    text.setSelectionRange(selection.y, offset - selection.y);
                else
                    text.setSelectionRange(selection.x, offset - selection.x);
            }
        }
    }
    
    protected class SelectPreviousSubWordAction extends PreviousSubWordAction {
        public SelectPreviousSubWordAction() {
            super(ST.SELECT_WORD_PREVIOUS);
            setActionDefinitionId(ITextEditorActionDefinitionIds.SELECT_WORD_PREVIOUS);
        }
        @Override
        protected void setCaretPosition(final int position) {
            final ISourceViewer viewer= getSourceViewer();

            final StyledText text= viewer.getTextWidget();
            if (text != null && !text.isDisposed()) {

                final Point selection= text.getSelection();
                final int caret= text.getCaretOffset();
                final int offset= modelOffset2WidgetOffset(viewer, position);

                if (caret == selection.x)
                    text.setSelectionRange(selection.y, offset - selection.y);
                else
                    text.setSelectionRange(selection.x, offset - selection.x);
            }
        }
    }
    
    protected class DeleteNextSubWordAction extends NextSubWordAction implements IUpdate {
        public DeleteNextSubWordAction() {
            super(ST.DELETE_WORD_NEXT);
            setActionDefinitionId(ITextEditorActionDefinitionIds.DELETE_NEXT_WORD);
        }
        @Override
        protected void setCaretPosition(final int position) {
            if (!validateEditorInputState())
                return;

            final ISourceViewer viewer= getSourceViewer();
            StyledText text= viewer.getTextWidget();
            Point selection= viewer.getSelectedRange();
            final int caret, length;
            if (selection.y != 0) {
                caret= selection.x;
                length= selection.y;
            } else {
                caret= widgetOffset2ModelOffset(viewer, text.getCaretOffset());
                length= position - caret;
            }

            try {
                viewer.getDocument().replace(caret, length, ""); //$NON-NLS-1$
            } catch (BadLocationException exception) {
                // Should not happen
            }
        }
        public void update() {
            setEnabled(isEditorInputModifiable());
        }
    }

    protected class DeletePreviousSubWordAction extends PreviousSubWordAction implements IUpdate {
        public DeletePreviousSubWordAction() {
            super(ST.DELETE_WORD_PREVIOUS);
            setActionDefinitionId(ITextEditorActionDefinitionIds.DELETE_PREVIOUS_WORD);
        }
        @Override
        protected void setCaretPosition(int position) {
            if (!validateEditorInputState())
                return;

            final int length;
            final ISourceViewer viewer= getSourceViewer();
            StyledText text= viewer.getTextWidget();
            Point selection= viewer.getSelectedRange();
            if (selection.y != 0) {
                position= selection.x;
                length= selection.y;
            } else {
                length= widgetOffset2ModelOffset(viewer, text.getCaretOffset()) - position;
            }

            try {
                viewer.getDocument().replace(position, length, ""); //$NON-NLS-1$
            } catch (BadLocationException exception) {
                // Should not happen
            }
        }
        public void update() {
            setEnabled(isEditorInputModifiable());
        }
    }

    @Override
    public Object getAdapter(Class required) {
        if (IToggleBreakpointsTarget.class.equals(required)) {
            if (toggleBreakpointTarget == null) {
                toggleBreakpointTarget = new ToggleBreakpointAdapter();
            }
            return toggleBreakpointTarget;
        }
        return super.getAdapter(required);
    }

    public void dispose() {
        getParseController().dispose();
        super.dispose();
        if (fResourceListener != null) {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(fResourceListener);
        }
        //CeylonPlugin.getInstance().getPreferenceStore().removePropertyChangeListener(colorChangeListener);
        currentTheme.getColorRegistry().removeListener(colorChangeListener);
        currentTheme.getFontRegistry().removeListener(fontChangeListener);
        setSourceViewerConfiguration(new SourceViewerConfiguration());
    }
}
