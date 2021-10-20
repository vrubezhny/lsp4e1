/*******************************************************************************
 * Copyright (c) 2021 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Victor Rubezhny (Red Hat Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.operations.linkedediting;

import java.net.URI;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentCommand;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IAutoEditStrategy;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ISynchronizable;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.IPostSelectionProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4j.LinkedEditingRanges;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.swt.custom.StyledText;

public class LSPLinkedEditingReconcilingStrategy implements IReconcilingStrategy, IReconcilingStrategyExtension, IPreferenceChangeListener, IDocumentListener,  IAutoEditStrategy {
	public static final String LINKED_EDITING_PREFERENCE = "org.eclipse.ui.genericeditor.linkedediting"; //$NON-NLS-1$
	public static final String LINKEDEDITING_ANNOTATION_TYPE = "org.eclipse.lsp4e.linkedediting"; //$NON-NLS-1$

	private boolean enabled;
	private ISourceViewer sourceViewer;
	private IDocument fDocument;
	private EditorSelectionChangedListener editorSelectionChangedListener;
	private CompletableFuture<?> request;
	private Job highlightJob;

	/**
	 * Holds the current linkedEditing Ranges
	 */
//	static Map<IDocument, Set<LinkedEditingRanges>> fLinkedEditingRanges = new HashMap<>();
	static Map<IDocument, LinkedEditingRanges> fLinkedEditingRanges = new HashMap<>();

	/**
	 * Holds the current linkedEditing annotations.
	 */
	private Annotation[] fLinkedEditingAnnotations = null;


	public LSPLinkedEditingReconcilingStrategy() {
	}

	private CompletableFuture<Void> collectLinkedEditingHighlights(int offset) {
		if (sourceViewer == null || fDocument == null || !enabled) {
			return CompletableFuture.completedFuture(null);
		}
		cancel();
		fLinkedEditingRanges.put(fDocument, null);
		Position position;
		try {
			position = LSPEclipseUtils.toPosition(offset, fDocument);
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			return CompletableFuture.completedFuture(null);
		}
		URI uri = LSPEclipseUtils.toUri(fDocument);
		if(uri == null) {
			return CompletableFuture.completedFuture(null);
		}
		long start = System.currentTimeMillis();
		TextDocumentIdentifier identifier = new TextDocumentIdentifier(uri.toString());
		TextDocumentPositionParams params = new TextDocumentPositionParams(identifier, position);

		return LanguageServiceAccessor.getLanguageServers(fDocument,
					capabilities -> LSPEclipseUtils.hasCapability(capabilities.getLinkedEditingRangeProvider()))
				.thenComposeAsync(languageServers ->
					CompletableFuture.allOf(languageServers.stream()
							.map(ls -> ls.getTextDocumentService().linkedEditingRange(LSPEclipseUtils.toLinkedEditingRangeParams(params))
//							).map(request -> request
									.thenAcceptAsync(result -> {
								System.out.println(System.currentTimeMillis() + ": collectLinkedEditingHighlights: add a range"); //$NON-NLS-1$
								fLinkedEditingRanges.put(fDocument, result);
								}
							)).toArray(CompletableFuture[]::new)));
	}

	class EditorSelectionChangedListener implements ISelectionChangedListener {

		public void install(ISelectionProvider selectionProvider) {
			if (selectionProvider == null)
				return;

			if (selectionProvider instanceof IPostSelectionProvider) {
				IPostSelectionProvider provider = (IPostSelectionProvider) selectionProvider;
				provider.addPostSelectionChangedListener(this);
			} else {
				selectionProvider.addSelectionChangedListener(this);
			}
		}

		public void uninstall(ISelectionProvider selectionProvider) {
			if (selectionProvider == null)
				return;

			if (selectionProvider instanceof IPostSelectionProvider) {
				IPostSelectionProvider provider = (IPostSelectionProvider) selectionProvider;
				provider.removePostSelectionChangedListener(this);
			} else {
				selectionProvider.removeSelectionChangedListener(this);
			}
		}

		@Override
		public void selectionChanged(SelectionChangedEvent event) {
			System.out.println(System.currentTimeMillis() + ": updateLinkedEditingHighlights: selectionChanged: " + (event.getSelection().toString())); //$NON-NLS-1$
			updateLinkedEditingHighlights(event.getSelection());
		}
	}

	public void install(ITextViewer viewer) {
		if (!(viewer instanceof ISourceViewer)) {
			return;
		}
		IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID);
		preferences.addPreferenceChangeListener(this);
		this.enabled = preferences.getBoolean(LINKED_EDITING_PREFERENCE, true);
		this.sourceViewer = (ISourceViewer) viewer;
		editorSelectionChangedListener = new EditorSelectionChangedListener();
		editorSelectionChangedListener.install(sourceViewer.getSelectionProvider());
	}

	public void uninstall() {
		if (sourceViewer != null) {
			editorSelectionChangedListener.uninstall(sourceViewer.getSelectionProvider());
		}
		IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID);
		preferences.removePreferenceChangeListener(this);
		cancel();
	}

	@Override
	public void preferenceChange(PreferenceChangeEvent event) {
		if (event.getKey().equals(LINKED_EDITING_PREFERENCE)) {
			this.enabled = Boolean.valueOf(event.getNewValue().toString());
			if (enabled) {
				initialReconcile();
			} else {
				removeLinkedEditingAnnotations();
			}
		}
	}

	@Override
	public void setProgressMonitor(IProgressMonitor monitor) {
	}

	@Override
	public void initialReconcile() {
		if (sourceViewer != null) {
			ISelectionProvider selectionProvider = sourceViewer.getSelectionProvider();
			final StyledText textWidget = sourceViewer.getTextWidget();
			if (textWidget != null && selectionProvider != null) {
				textWidget.getDisplay().asyncExec(() -> {
					if (!textWidget.isDisposed()) {
						updateLinkedEditingHighlights(selectionProvider.getSelection());
					}
				});
			}
		}
	}

	@Override
	public void setDocument(IDocument document) {
		if (this.fDocument != null) {
			this.fDocument.removeDocumentListener(this);
		}

		this.fDocument = document;

		if (this.fDocument != null) {
			this.fDocument.addDocumentListener(this);
		}
	}

	@Override
	public void reconcile(DirtyRegion dirtyRegion, IRegion subRegion) {
	}

	@Override
	public void reconcile(IRegion partition) {
	}

	/*
	 * This implementation create a compound command in order to change the only required
	 * amount of characters in linked editing ranges, but in order to make it work correctly
	 * the `org.eclipse.jface.text.DocumentCommand.fillEvent(VerifyEvent, IRegion)` method is
	 * to be fixed in order to make it supporting such a compound command:
	 * ```
	 * diff --git a/org.eclipse.jface.text/src/org/eclipse/jface/text/DocumentCommand.java b/org.eclipse.jface.text/src/org/eclipse/jface/text/DocumentCommand.java
	 * index cc6958e83..522551ef2 100644
	 * --- a/org.eclipse.jface.text/src/org/eclipse/jface/text/DocumentCommand.java
	 * +++ b/org.eclipse.jface.text/src/org/eclipse/jface/text/DocumentCommand.java
	 * @@ -292,7 +292,7 @@ public class DocumentCommand {
	 *         boolean fillEvent(VerifyEvent event, IRegion modelRange) {
	 *                 event.text= text;
	 * -               event.doit= (offset == modelRange.getOffset() && length == modelRange.getLength() && doit && caretOffset == -1);
	 * +               event.doit= (offset == modelRange.getOffset() && length == modelRange.getLength() && getCommandCount() == 1 && doit && caretOffset == -1);
	 *                 return event.doit;
	 *         }
 	 * ```
	 * Otherwise, `org.eclipse.jface.text.TextViewer.handleVerifyEvent(VerifyEvent)` doesn't create a compound change based on the DocumentCommand.
	 *
	@Override
	public void customizeDocumentCommand(IDocument document, DocumentCommand command) {
		synchronized (fLinkedEditingRanges) {
			LinkedEditingRanges ranges = getLinkedEditingRanges(document);
			if (ranges == null) {
				return;
			}

			Range commandRange = null;
			int delta;
			try {
				for (Range r : ranges.getRanges()) {
					int start = LSPEclipseUtils.toOffset(r.getStart(), document);
					int end = LSPEclipseUtils.toOffset(r.getEnd(), document);

					if (start <= command.offset && end > command.offset) {
						commandRange = r;
						delta = command.offset - start;
						break;
					}
				}
			} catch (BadLocationException e) {
				return;
			}

			if (commandRange == null) {
				return;
			}

			final Range rangeAtCursor = commandRange;
			ranges.getRanges().forEach(r -> {
				if (rangeAtCursor != r) {
					try {
						int start = LSPEclipseUtils.toOffset(r.getStart(), document) + delta;
						command.addCommand(start, command.length, command.text, command.owner);
					} catch (BadLocationException e) {
						e.printStackTrace();
					}
				}
			});
		}
	}
	*/

	@Override
	public void customizeDocumentCommand(IDocument document, DocumentCommand command) {
		System.out.println(System.currentTimeMillis() + ": customizeDocumentCommand: >>>"); //$NON-NLS-1$

		LinkedEditingRanges ranges = fLinkedEditingRanges.get(document);
		if (ranges == null) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			ranges = fLinkedEditingRanges.get(document);
		}
		if (ranges == null) {
			System.out.println(System.currentTimeMillis() + ": customizeDocumentCommand: <<< Empty ranges!"); //$NON-NLS-1$
			return;
		}



		Set<Range> sortedRanges = new TreeSet<>(RANGE_OFFSET_ORDER);
		sortedRanges.addAll(ranges.getRanges());

		int changeStart = Integer.MAX_VALUE;
		int changeEnd = Integer.MIN_VALUE;
		Range commandRange = null;
		int delta = 0;
		try {
			for (Range r : sortedRanges) {
				int start = LSPEclipseUtils.toOffset(r.getStart(), document);
				if (changeStart > start) {
					changeStart = start;
				}
				int end = LSPEclipseUtils.toOffset(r.getEnd(), document);
				if (changeEnd < end) {
					changeEnd = end;
				}

				if (start <= command.offset && end >= command.offset) {
					commandRange = r;
					delta = command.offset - start;
				}
			}
		} catch (BadLocationException e) {
			return;
		}

		if (commandRange == null) {
			System.out.println(System.currentTimeMillis() + ": customizeDocumentCommand: Command is not inside ranges!"); //$NON-NLS-1$
			return;
		}


		StringBuilder text = new StringBuilder();
		int caretOffset = -1;
		try {
			int currentOffset = changeStart;
			for (Range r : sortedRanges) {
				int rangeStart = LSPEclipseUtils.toOffset(r.getStart(), document);
				int rangeEnd = LSPEclipseUtils.toOffset(r.getEnd(), document);
				if (currentOffset < rangeStart) {
					text.append(document.get(currentOffset, rangeStart - currentOffset));
				}

				int rangeChangeEnd = rangeStart + delta + command.length;
				String rangeTextBeforeCommand = document.get(rangeStart, delta);
				String rangeTextAfterCommand = rangeEnd > rangeChangeEnd ?
						document.get(rangeChangeEnd, rangeEnd - rangeChangeEnd) :
							""; //$NON-NLS-1$

				text.append(rangeTextBeforeCommand).append(command.text);
				if (r == commandRange) {
					caretOffset = text.length();
				}
				text.append(rangeTextAfterCommand);
				currentOffset = rangeEnd > rangeChangeEnd ? rangeEnd : rangeChangeEnd;
			}
		} catch (BadLocationException e) {
			return;
		}

		command.offset = changeStart;
		command.length = changeEnd - changeStart;
		command.text = text.toString();
		command.caretOffset = changeStart + caretOffset;
		command.shiftsCaret = false;
	}

	@Override
	public void documentAboutToBeChanged(DocumentEvent event) {
	}

	@Override
	public void documentChanged(DocumentEvent event) {
		updateLinkedEditingHighlights(event.getOffset());
	}

	/**
	 * Cancel the last call of 'linkedEditing'.
	 */
	private void cancel() {
		if (request != null && !request.isDone()) {
			request.cancel(true);
			request = null;
		}
	}

	private void updateLinkedEditingHighlights(ISelection selection) {
		if (selection instanceof ITextSelection) {
			System.out.println(System.currentTimeMillis() + ": updateLinkedEditingHighlights: text selectionChanged: " + ((ITextSelection) selection).getOffset()); //$NON-NLS-1$

			updateLinkedEditingHighlights(((ITextSelection) selection).getOffset());
		}
	}

	private void updateLinkedEditingHighlights(int offset) {
		try {
			collectLinkedEditingHighlights(offset)
			.get(500, TimeUnit.MILLISECONDS);
//			.thenAcceptAsync(theVoid -> updateLinkedEditingHighlights());
			updateLinkedEditingHighlights();
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			e.printStackTrace();
		}
	}

	private void updateLinkedEditingHighlights() {
		if (highlightJob != null) {
			highlightJob.cancel();
		}
		highlightJob = Job.createSystem("LSP4E Linked Editing Highlight", //$NON-NLS-1$
				(ICoreRunnable)(monitor -> {
					updateLinkedEditingAnnotations(
							sourceViewer.getAnnotationModel(), monitor);
					}));
		highlightJob.schedule();
	}

	/**
	 * Update the UI annotations with the given list of LinkedEditing.
	 *
	 * @param highlights
	 *            list of LinkedEditing
	 * @param annotationModel
	 *            annotation model to update.
	 */
	private void updateLinkedEditingAnnotations(IAnnotationModel annotationModel, IProgressMonitor monitor) {
		System.out.println(System.currentTimeMillis() + ": updateLinkedEditingAnnotations: >>>"); //$NON-NLS-1$
		LinkedEditingRanges ranges = fLinkedEditingRanges.get(fDocument);
		if (monitor.isCanceled()) {
			System.out.println(System.currentTimeMillis() + ": updateLinkedEditingAnnotations: <<< canceled or null ranges"); //$NON-NLS-1$
			return;
		}

		Map<Annotation, org.eclipse.jface.text.Position> annotationMap = new HashMap<>(ranges == null ? 0 : ranges.getRanges().size());
		if (ranges != null) {
			for (Range r : ranges.getRanges()) {
				try {
					int start = LSPEclipseUtils.toOffset(r.getStart(), fDocument);
					int end = LSPEclipseUtils.toOffset(r.getEnd(), fDocument);
					annotationMap.put(new Annotation(LINKEDEDITING_ANNOTATION_TYPE, false, null),
							new org.eclipse.jface.text.Position(start, end - start));
				} catch (BadLocationException e) {
					System.out.println(System.currentTimeMillis() + ": updateLinkedEditingAnnotations: BLE: " + e.getMessage()); //$NON-NLS-1$
					e.printStackTrace();
					LanguageServerPlugin.logError(e);
				}
			}
		}
		synchronized (getLockObject(annotationModel)) {
			if (annotationModel instanceof IAnnotationModelExtension) {
				((IAnnotationModelExtension) annotationModel).replaceAnnotations(fLinkedEditingAnnotations, annotationMap);
			} else {
				removeLinkedEditingAnnotations();
				Iterator<Entry<Annotation, org.eclipse.jface.text.Position>> iter = annotationMap.entrySet().iterator();
				while (iter.hasNext()) {
					Entry<Annotation, org.eclipse.jface.text.Position> mapEntry = iter.next();
					annotationModel.addAnnotation(mapEntry.getKey(), mapEntry.getValue());
				}
			}
			fLinkedEditingAnnotations = annotationMap.keySet().toArray(new Annotation[annotationMap.keySet().size()]);
			System.out.println(System.currentTimeMillis() + ": updateLinkedEditingAnnotations: <<< OK"); //$NON-NLS-1$
		}
	}

	/**
	 * Returns the lock object for the given annotation model.
	 *
	 * @param annotationModel
	 *            the annotation model
	 * @return the annotation model's lock object
	 */
	private Object getLockObject(IAnnotationModel annotationModel) {
		if (annotationModel instanceof ISynchronizable) {
			Object lock = ((ISynchronizable) annotationModel).getLockObject();
			if (lock != null)
				return lock;
		}
		return annotationModel;
	}

	void removeLinkedEditingAnnotations() {
//		fLinkedEditingRanges.put(this.document, null);
		IAnnotationModel annotationModel = sourceViewer.getAnnotationModel();
		if (annotationModel == null || fLinkedEditingAnnotations == null)
			return;

		synchronized (getLockObject(annotationModel)) {
			if (annotationModel instanceof IAnnotationModelExtension) {
				((IAnnotationModelExtension) annotationModel).replaceAnnotations(fLinkedEditingAnnotations, null);
			} else {
				for (Annotation fOccurrenceAnnotation : fLinkedEditingAnnotations)
					annotationModel.removeAnnotation(fOccurrenceAnnotation);
			}
			fLinkedEditingAnnotations = null;
		}
	}

    /**
     * A Comparator that orders {@code Region} objects by offset
     */
    private static final Comparator<Range> RANGE_OFFSET_ORDER
                                         = new RangeOffsetComparator();
    private static class RangeOffsetComparator
            implements Comparator<Range> {

    	@Override
		public int compare(Range r1, Range r2) {
            Position p1 = r1.getStart();
            Position p2 = r2.getStart();

            if (p1.getLine() == p2.getLine()) {
            	return p1.getCharacter() - p2.getCharacter();
            }

            return p1.getLine() - p2.getLine();
        }
    }
}