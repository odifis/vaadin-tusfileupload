package com.asaoweb.vaadin.tusfileupload.ui;

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.vaadin.ui.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asaoweb.vaadin.tusfileupload.Config;
import com.asaoweb.vaadin.tusfileupload.FileInfo;
import com.asaoweb.vaadin.tusfileupload.component.TusMultiUpload;
import com.asaoweb.vaadin.tusfileupload.data.FileInfoThumbProvider;
import com.asaoweb.vaadin.tusfileupload.events.Events.AbstractTusUploadEvent;
import com.asaoweb.vaadin.tusfileupload.events.Events.FailedEvent;
import com.asaoweb.vaadin.tusfileupload.events.Events.FailedListener;
import com.asaoweb.vaadin.tusfileupload.events.Events.FileDeletedClickEvent;
import com.asaoweb.vaadin.tusfileupload.events.Events.FileDeletedClickListener;
import com.asaoweb.vaadin.tusfileupload.events.Events.FileIndexMovedEvent;
import com.asaoweb.vaadin.tusfileupload.events.Events.FileIndexMovedListener;
import com.asaoweb.vaadin.tusfileupload.events.Events.ProgressEvent;
import com.asaoweb.vaadin.tusfileupload.events.Events.ProgressListener;
import com.asaoweb.vaadin.tusfileupload.events.Events.StartedEvent;
import com.asaoweb.vaadin.tusfileupload.events.Events.StartedListener;
import com.asaoweb.vaadin.tusfileupload.events.Events.SucceededEvent;
import com.asaoweb.vaadin.tusfileupload.events.Events.SucceededListener;
import com.asaoweb.vaadin.tusfileupload.exceptions.TusException.ConfigError;
import com.vaadin.icons.VaadinIcons;
import com.vaadin.server.Resource;
import com.vaadin.shared.Registration;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.shared.ui.dnd.DropEffect;
import com.vaadin.shared.ui.dnd.EffectAllowed;
import com.vaadin.ui.Notification.Type;
import com.vaadin.ui.dnd.DragSourceExtension;
import com.vaadin.ui.dnd.DropTargetExtension;

public class TusMultiUploadLayout extends VerticalLayout {
	  private final static Method FILE_DELETED_METHOD;
	  private final static Method FILE_MOVED_METHOD;

	  static {
		    try {
		    	FILE_DELETED_METHOD = FileDeletedClickListener.class.getMethod(
		          "fileDeletedClick", FileDeletedClickEvent.class);
		    	FILE_MOVED_METHOD = FileIndexMovedListener.class.getMethod(
				  "fileIndexMoved", FileIndexMovedEvent.class);
		    }
		    catch (NoSuchMethodException | SecurityException ex) {
		      throw new RuntimeException("Unable to find listener event method.", ex);
		    }
		  }
	  
	  private static final Logger logger = LoggerFactory.getLogger(TusMultiUploadLayout.class.getName());

	protected final TusMultiUpload uploadButton;
	protected final Label infoLabel;
	protected final Panel listPanel;
	
	protected final List<FileInfo> files;
	
	protected AbstractOrderedLayout fileListLayout;
	protected boolean 	allowReorder = false;
	protected boolean 	reverseOrder = false;
	protected boolean 	compactLayout = false;
	protected FileInfoThumbProvider provider;
	protected String 	infoLabelMessagePattern = "{0,,filenb} uploaded files / {2,,totalSize} (+{1,,queueSize} queued)";
	protected String	fileMinCountErrorMessagePattern = "Can't delete any file: {0,,minCount} files must be attached. Upload new files first.";
	protected String 	noFilesUploaded = "No files uploaded yet.";

	protected boolean 	allowDelete = true;
	protected int		minFileCount = 0;
	protected boolean   cachedHTML5DnD = false;

	protected Lock		updateLock = new ReentrantLock();
	
	public TusMultiUploadLayout() throws ConfigError {
		this(null, new Config(), new ArrayList<FileInfo>(), null, false);
	}	
	public TusMultiUploadLayout(String buttonCaption) throws ConfigError {
		this(null, new Config(), new ArrayList<FileInfo>(), null, false);
	}
	
	public TusMultiUploadLayout(String buttonCaption, Config config, List<FileInfo> existingFiles, FileInfoThumbProvider provider, boolean allowReorder) throws ConfigError {
		super();
		uploadButton = new TusMultiUpload(buttonCaption, config);
		infoLabel = new Label();
		listPanel = new Panel();
		listPanel.setSizeFull();
		HorizontalLayout infobar = new HorizontalLayout(uploadButton, infoLabel);
		infobar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
		infobar.setExpandRatio(infoLabel, 1f);
		
		this.setHeight(300, Unit.PIXELS);
		this.setWidth(100, Unit.PERCENTAGE);
		this.addComponents(listPanel, infobar);
		this.setExpandRatio(listPanel, 1f);
		this.addStyleName("tusmultiuploadlayout");

		files = existingFiles;
		uploadButton.addFileQueuedListener(e -> {
			addFileInfoItem(e.getFileInfo());
			refreshFilesInfos();
		});
		setThumbProvider(provider);
		allowReorder(allowReorder);
				
	}
	
	@Override
	public void attach() {
		refreshFileList();
		super.attach();
        if (allowReorder && getUI() != null) {
            cachedHTML5DnD = getUI().isMobileHtml5DndEnabled();
            // loads external polyfill: https://vaadin.com/docs/v8/framework/advanced/advanced-dragndrop.html
            // Drag and Drop is mutually exclusive with context click on mobile devices
            getUI().setMobileHtml5DndEnabled(true);
        }
	}

    @Override
    public void detach() {
	    super.detach();
        if (allowReorder && getUI() != null) {
            getUI().setMobileHtml5DndEnabled(cachedHTML5DnD);
        }
    }
	
	public void setThumbProvider(FileInfoThumbProvider provider) {
		this.provider = provider;
	}
	
	public Registration addSucceededListener(SucceededListener listener) {
		return uploadButton.addSucceededListener(listener);
	}
	
	public Registration addFileDeletedClickListener(FileDeletedClickListener listener) {
	    return addListener(FileDeletedClickEvent.class, listener, FILE_DELETED_METHOD);
	}

	public Registration addFileIndexMovedListener(FileIndexMovedListener listener) {
	    return addListener(FileIndexMovedEvent.class, listener, FILE_MOVED_METHOD);
	}
	
	public void refreshFileList() {
		if (compactLayout && (fileListLayout == null || fileListLayout instanceof VerticalLayout )) {
			fileListLayout = new HorizontalLayout();
			fileListLayout.setStyleName("tusmultiuploadlayout-float-container");
			fileListLayout.setSpacing(false);
			listPanel.setContent(fileListLayout);
		} else if ( !compactLayout && (fileListLayout == null || fileListLayout instanceof HorizontalLayout )){
			fileListLayout = new VerticalLayout();
			listPanel.setContent(fileListLayout);
		}
		
		fileListLayout.removeAllComponents();
		updateLock.lock();
		try {
			files.forEach(this::addFileInfoItem);
		} finally {
			updateLock.unlock();
		}
		refreshFilesInfos();
	}
	
	public void refreshFilesInfos() {
		int fileNB = files.size();
		int queueNB = fileListLayout.getComponentCount() - fileNB;
		uploadButton.setRemainingQueueSeats(uploadButton.getMaxFileCount()-fileNB);
		long totalUploadedSize = files.stream().mapToLong(fi -> fi.entityLength).sum();
		infoLabel.setValue( MessageFormat.format(infoLabelMessagePattern, fileNB, queueNB, TusMultiUpload.readableFileSize(totalUploadedSize) ));
		if (fileNB == 0 && fileListLayout.getComponentCount() == 0) {
			fileListLayout.addComponent(new Label(noFilesUploaded));
		}
	}

	public boolean hasUploadInProgress() {
		return this.getUploader().hasUploadInProgress();
	}

	public int getQueueCount() {
		/*int fileNB = files.size();
		int queueNB = fileListLayout.getComponentCount() - fileNB;*/
		int queueNB = this.getUploader().getQueueCount();
		logger.debug("getQueueCount: {}", queueNB);
		return queueNB;
	}

	public boolean hasRemainingQueue() {
		return getQueueCount() > 0;
	}

    /**
     * To replace with final files wrapper after upload succeeded
     * @param originalFi
     * @param newFi
     */
    public void replaceFileInfoItem(FileInfo originalFi, FileInfo newFi) {
	    if (originalFi == null || newFi == null) {
	        return;
        }
		updateLock.lock();
	    try {
			fileListLayout.forEach(fl -> {
				if (fl instanceof FileListComponent) {
					FileListComponent flc = (FileListComponent) fl;
					if (flc.fileInfo != null && flc.fileInfo.id != null && flc.fileInfo.id.equals(originalFi.id)) {
						flc.fileInfo = newFi;
					}
				}
			});
		} finally {
			updateLock.unlock();
		}
    }

    public void removeFileInfoItem(FileInfo fi) {
        if (fi.isUploading()) {
            logger.info("Won't delete {}: is uploading. Wait finish.", fi);
            return;
        }
        Iterator<Component> itr = this.fileListLayout.iterator();
        List<Component> tbd = new ArrayList<>();
		updateLock.lock();
		try {
			while (itr.hasNext()) {
				Component c = itr.next();
				if (c instanceof FileListComponent) {
					FileListComponent flc = (FileListComponent) c;
					if (flc.getFileInfo().equals(fi)) {
						if (flc.getFileInfo().isQueued()) {
							uploadButton.removeFromQueue(flc.getFileInfoQId());
						}
						tbd.add(c);
						files.remove(fi);
						break;
					}
				}
			}
		} finally {
			updateLock.unlock();
		}
        tbd.forEach(fileListLayout::removeComponent);
    }

    private void addFileInfoItem(FileInfo fi) {
		if (reverseOrder) {
			fileListLayout.addComponentAsFirst(new FileListComponent(fi, uploadButton));
		} else {
			fileListLayout.addComponent(new FileListComponent(fi, uploadButton));
		}
	}

	public void cancelSucceededEvent(SucceededEvent event) {
		updateLock.lock();
		try {
			this.files.removeIf(f -> f.id != null && f.id.equals(event.getId()));
		} finally {
			updateLock.unlock();
		}
	    refreshFileList();
    }
	
	/**
	 * Default: "{0,,filenb} uploaded files / {2,,totalSize} (+{1,,queueSize} queued)"
	 * 
	 * @param pattern used in MessageFormat.format
	 */
	public void setInfoPanelMessagePattern(String pattern) {
		infoLabelMessagePattern = pattern;
	}
	
	public void setFileMinCountErrorMessagePattern(String pattern) {
		fileMinCountErrorMessagePattern = pattern;
	}
	
	public void setMinFileCount(int minFileCount) {
		this.minFileCount = minFileCount;
		if (fileListLayout != null && fileListLayout.isAttached()) {
			refreshFileList();
		}
	}

	public int getMaxFileCount() {
		return uploadButton.getMaxFileCount();
	}

	public int getMinFileCount() {
		return minFileCount;
	}
	
	public void setChunkSize(long chunkSize) {
		uploadButton.setChunkSize(chunkSize);
	}
	
	public void setWithCredentials(boolean withCredentials) {
		uploadButton.setWithCredentials(withCredentials);
	}
	
	public TusMultiUpload getUploader() {
		return uploadButton;
	}
	
	public void allowReorder(boolean allowReorder) {
		this.allowReorder = allowReorder;
		if (fileListLayout != null && fileListLayout.isAttached()) {
			refreshFileList();
		}
	}
	
	public void setCompactLayout(boolean compactLayout) {
		this.compactLayout = compactLayout;
		if (fileListLayout != null && fileListLayout.isAttached()) {
			refreshFileList();
		}
	}
	
	/**
	 * Display file list in reverse order (last as first component)
	 * 
	 */
	public void setReverseOrder(boolean reverseOrder) {
		this.reverseOrder = reverseOrder;
		if (fileListLayout != null && fileListLayout.isAttached()) {
			refreshFileList();
		}
	}
	
	public void setAllowDelete(boolean allowDelete) {
		this.allowDelete = allowDelete;
		if (fileListLayout != null && fileListLayout.isAttached()) {
			refreshFileList();
		}
	}
	
	protected class FileListComponent extends HorizontalLayout implements FailedListener, ProgressListener, SucceededListener, StartedListener {
		protected static final String PROGRESS_STYLE = "progress";
		protected static final String FAILED_STYLE = "failed";

		protected final Image thumb = new Image();
		protected final Label filename = new Label();
		protected final Label mimeType = new Label();
		protected final Label fileSize = new Label();
		protected final Label errorMessage = new Label();
		protected final ProgressBar progress = new ProgressBar();
		protected final VerticalLayout statusWrapper;
		protected final Label progressInfos = new Label();
		protected final AbstractOrderedLayout progressBarWrapper;
		protected final Button action = new Button();

		protected final Lock flcUpdateLock = new ReentrantLock();

        protected FileInfo fileInfo;
        protected Registration rFailed, rStarted, rProgress, rSucceeded;
						
		public FileListComponent(FileInfo fileInfo, TusMultiUpload uploader) {
			super();
			this.fileInfo = fileInfo;	
			
			thumb.addStyleName("thumb");
			filename.setValue(fileInfo.suggestedFilename);
			filename.addStyleName("filename");
			mimeType.setValue(fileInfo.suggestedFiletype);
			mimeType.addStyleName("filetype");
			if (fileInfo.entityLength > 0) {
				fileSize.setValue( TusMultiUpload.readableFileSize(fileInfo.entityLength));
			} else {
				fileSize.setVisible(false);
			}
			fileSize.addStyleName("filesize");
			progress.setWidth("100%");
			progress.setStyleName("progress-bar");
			errorMessage.setVisible(false);
			errorMessage.setWidth("100%");
			progressInfos.addStyleName("progress-infos");

            progressBarWrapper = TusMultiUploadLayout.this.compactLayout ? new VerticalLayout() : new HorizontalLayout();
			progressBarWrapper.addComponents(progress, progressInfos);
			progressBarWrapper.setVisible(false);
            progressBarWrapper.setMargin(false);
            progressBarWrapper.setSpacing(false);
			progressBarWrapper.setWidth("100%");
			progressBarWrapper.setExpandRatio(progress, 1.0f);

			statusWrapper = new VerticalLayout(errorMessage, progressBarWrapper);
			statusWrapper.setWidth("100%");
			statusWrapper.setMargin(false);
			statusWrapper.addStyleName("progress-wrapper");
			
			action.addClickListener((e) -> {
				boolean canDelete = TusMultiUploadLayout.this.minFileCount <= TusMultiUploadLayout.this.files.size() - 1;
				if (this.fileInfo.isQueued()) {
					uploadButton.removeFromQueue(this.fileInfo.queueId);
				} else if ( canDelete ) {
					TusMultiUploadLayout.this.fireEvent(new FileDeletedClickEvent(TusMultiUploadLayout.this.uploadButton, this.fileInfo));
				} else {
					Notification.show(MessageFormat.format(TusMultiUploadLayout.this.fileMinCountErrorMessagePattern, TusMultiUploadLayout.this.minFileCount), Type.ERROR_MESSAGE);
				}
				if (canDelete || this.fileInfo.isQueued()) {
					TusMultiUploadLayout.this.updateLock.lock();
					try {
						TusMultiUploadLayout.this.files.remove(this.fileInfo);
					} finally {
						TusMultiUploadLayout.this.updateLock.unlock();
					}
					TusMultiUploadLayout.this.fileListLayout.removeComponent(this);
					TusMultiUploadLayout.this.refreshFilesInfos();
				}
			});
			
			if (TusMultiUploadLayout.this.compactLayout) {
				VerticalLayout compactWrapper = new VerticalLayout();
                compactWrapper.setMargin(false);
                compactWrapper.addStyleName("tusmultiuploadlayout-filelistcomponent-compact-wrapper");
				CssLayout thumbWrapper = new CssLayout();
				thumbWrapper.addStyleName("tusmultiuploadlayout-filelistcomponent-compact-thumbwrapper");
				thumbWrapper.addComponent(thumb);
				thumbWrapper.addComponent(action);
				thumbWrapper.addComponent(statusWrapper);
				thumbWrapper.addComponent( fileSize);
				fileSize.setWidth("100%");
				statusWrapper.setWidth("100%");
				
				thumb.setSizeFull();				
				filename.setWidth("100%");
				//action.setWidth("auto");
				//action.setHeight("auto");

				compactWrapper.addComponents(thumbWrapper, filename);
				//this.setHeight(compactSize+20, Unit.PIXELS);
				this.addComponents(compactWrapper);
				this.addStyleName("tusmultiuploadlayout-filelistcomponent-compact");
				
			} else {
				/*thumb.setWidth(30, Unit.PIXELS);
				filename.setWidth(150, Unit.PIXELS);
				mimeType.setWidth(80, Unit.PIXELS);
				fileSize.setWidth(80, Unit.PIXELS);*/
				this.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
				VerticalLayout vLayout = new VerticalLayout();
                vLayout.setMargin(false);
                vLayout.setWidth("100%");
				HorizontalLayout infosLine = new HorizontalLayout(thumb, filename, mimeType, fileSize, action);
				infosLine.setWidth("100%");
				infosLine.setExpandRatio(filename, 1f);
				vLayout.addComponents(infosLine, statusWrapper);
				this.addComponents(vLayout);
				this.setExpandRatio(vLayout, 1.0f);
				this.setWidth("100%");
				this.addStyleName("tusmultiuploadlayout-filelistcomponent");
			}
			
			if (fileInfo.isQueued()) {
				rFailed = uploader.addFailedListener(this);
				rStarted = uploader.addStartedListener(this);
				rProgress = uploader.addProgressListener(this);
				rSucceeded = uploader.addSucceededListener(this);
			}
			
			if (TusMultiUploadLayout.this.allowReorder) {
				if (!TusMultiUploadLayout.this.compactLayout) {
					Label picker = new Label(VaadinIcons.ELLIPSIS_DOTS_V.getHtml());
					picker.setContentMode(ContentMode.HTML);
					this.addComponentAsFirst(picker);
				}
				
				DragSourceExtension<FileListComponent> dragSourceExt = new DragSourceExtension<>(this);
				// set the allowed effect
				dragSourceExt.setEffectAllowed(EffectAllowed.MOVE);
				
				if (allowReorder) {
					DropTargetExtension<FileListComponent> dropTarget = new DropTargetExtension<>(this);
					dropTarget.setDropEffect(DropEffect.MOVE);
					dropTarget.addDropListener(event -> {
					    // if the drag source is in the same UI as the target
					    Optional<AbstractComponent> dragSource = event.getDragSourceComponent();
						logger.debug("addDropListener {} for source {}", event, dragSource);
					    if (dragSource.isPresent() && dragSource.get() instanceof FileListComponent) {
					    	FileListComponent sourceFlc = (FileListComponent) dragSource.get();

					    	int targetIndex = TusMultiUploadLayout.this.fileListLayout.getComponentIndex(this);
					    	int currentIndex = TusMultiUploadLayout.this.fileListLayout.getComponentIndex(sourceFlc);
					    	if (targetIndex > currentIndex) {
					    		TusMultiUploadLayout.this.fileListLayout.addComponent(sourceFlc, targetIndex+1);
					    	} else {
					    		TusMultiUploadLayout.this.fileListLayout.addComponent(sourceFlc, targetIndex);					    		
					    	}
					    	
					    	if (reverseOrder) {
					    		int maxIndex = TusMultiUploadLayout.this.fileListLayout.getComponentCount() - 1;
						    	TusMultiUploadLayout.this.fireEvent(new FileIndexMovedEvent(TusMultiUploadLayout.this.uploadButton, sourceFlc.getFileInfo(), maxIndex - currentIndex, maxIndex - targetIndex));
					    	} else {
					    		TusMultiUploadLayout.this.fireEvent(new FileIndexMovedEvent(TusMultiUploadLayout.this.uploadButton, sourceFlc.getFileInfo(), currentIndex, targetIndex));
					    	}
					    }
					});
				}
			}
			
			update();
		}
		
		@Override
		  public void detach() {
			unregisterListeners();
		    super.detach();
		  }
		
		protected boolean isImage() {
			return fileInfo.suggestedFiletype != null && fileInfo.suggestedFiletype.toLowerCase().contains("image");
		}

		protected boolean isVideo() {
			return fileInfo.suggestedFiletype != null && fileInfo.suggestedFiletype.toLowerCase().contains("video");
		}
		
		public void unregisterListeners() {
			if (rFailed != null) {
				rFailed.remove();
				rProgress.remove();
				rSucceeded.remove();
			}
		}
		
		public String getFileInfoQId() {
			return fileInfo.queueId;
		}
		
		public FileInfo getFileInfo() {
			return fileInfo;
		}
		
		public void update() {
			this.removeStyleName("ui-queued");
			this.removeStyleName("ui-finished");
			
			if (fileInfo.isQueued()) {
				this.addStyleName("ui-queued");
				progressBarWrapper.setVisible(true);
				action.setIcon(VaadinIcons.CLOSE);
			} else if (fileInfo.isFinished()) {
				this.addStyleName("ui-finished");
				action.setIcon(VaadinIcons.TRASH);
				progressBarWrapper.setVisible(false);
				action.setVisible(TusMultiUploadLayout.this.allowDelete);
			}
			statusWrapper.setVisible(progressBarWrapper.isVisible() || errorMessage.isVisible());
			Resource thumbRsc;
			
			if ( provider != null && (thumbRsc = provider.getThumb(fileInfo)) != null ) {
				thumb.setIcon(null);
				thumb.setSource(thumbRsc);
			} else if ( isImage() ) {
				thumb.setIcon(VaadinIcons.FILE_PICTURE);						
			} else if ( isVideo() ) {
				thumb.setIcon(VaadinIcons.FILE_MOVIE);
			} else {
				thumb.setIcon(VaadinIcons.FILE_O);
			}
		}
		
		public void setProgress(long value, long total) {
			flcUpdateLock.lock();
			try {
				fileInfo.offset = value;
				progress.setValue((float) value / (float) total);
				int pct = (int) ((float) value / (float) total * 100);
				if (TusMultiUploadLayout.this.compactLayout) {
					progressInfos.setValue(TusMultiUpload.readableFileSize(value) + "/" + pct + "%");
				} else {
					progressInfos.setValue(TusMultiUpload.readableFileSize(value) + " / " + TusMultiUpload.readableFileSize(total) + " (" + pct + "%)");
				}
				errorMessage.setVisible(false);
				progressBarWrapper.setVisible(true);
				statusWrapper.setVisible(true);
				if (progress.getValue() >= 1) {
					update();
				}
			} finally {
				flcUpdateLock.unlock();
			}
		}
		
		public void setError(String message) {
			errorMessage.setVisible(false);
			progressBarWrapper.setVisible(false);
			statusWrapper.setVisible(true);
			errorMessage.setValue(message);
			this.addStyleName(FAILED_STYLE);
		}
		
		private boolean isEventOwner(AbstractTusUploadEvent evt) {
			logger.debug("FileListComponent.isEventOwner: {}(qId {}) for file component: {}", evt.getClass(), evt.getQueueId(), fileInfo);
			return fileInfo.queueId != null && fileInfo.queueId.equals(evt.getQueueId());
		}
		
		@Override
		public void uploadFailed(FailedEvent evt) {
			if ( isEventOwner(evt) ) {
				setError(evt.getReason().getMessage());
				unregisterListeners();
				this.removeStyleName(PROGRESS_STYLE);
				update();
			}
		}

		@Override
		public void uploadSucceeded(SucceededEvent evt) {
			if ( isEventOwner(evt) ) {
			    logger.debug("uploadSucceeded for evt {}", evt);
				flcUpdateLock.lock();
				try {
					if (evt.getFinalFileInfo() != null) {
						fileInfo = evt.getFinalFileInfo();
					} else if (evt.getId() != null && !evt.getId().isEmpty()) {
						fileInfo.id = evt.getId();
						fileInfo.offset = evt.getFileInfo().offset;
					}
					if (evt.shouldAddFileToList()) {
						TusMultiUploadLayout.this.updateLock.lock();
						try {
							TusMultiUploadLayout.this.files.add(this.fileInfo);
						} finally {
							TusMultiUploadLayout.this.updateLock.unlock();
						}
					}
				} finally {
					flcUpdateLock.unlock();
				}
				TusMultiUploadLayout.this.refreshFilesInfos();
				update();
				unregisterListeners();
				this.removeStyleName(PROGRESS_STYLE);

			}
		}

		@Override
		public void uploadProgress(ProgressEvent evt) {
			if ( isEventOwner(evt) ) {
				setProgress(evt.getFileInfo().offset, evt.getFileInfo().entityLength);
				this.addStyleName(PROGRESS_STYLE);
			}			
		}

		@Override
		public void uploadStarted(StartedEvent evt) {
			if ( isEventOwner(evt) ) {
				setProgress(evt.getFileInfo().offset, evt.getFileInfo().entityLength);
			}
		}
		
		
	}
	
	
}
