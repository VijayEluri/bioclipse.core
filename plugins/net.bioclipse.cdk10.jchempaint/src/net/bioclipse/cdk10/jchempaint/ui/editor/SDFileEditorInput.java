package net.bioclipse.cdk10.jchempaint.ui.editor;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.part.FileEditorInput;


public class SDFileEditorInput extends FileEditorInput{

    private int index;
    
    /**
     * A FileEditorInput that also holds an index to the MDLMolFile that should
     *  be used.
     * @param file
     * @param index
     */
    public SDFileEditorInput(IFile file, int index) {
        super( file );
        this.index=index;
    }
    
    @Override
    public boolean equals( Object obj ) {
        if (this == obj) {
            return true;
          }
          if (!(obj instanceof SDFileEditorInput)) {
            return false;
          }
          SDFileEditorInput other = (SDFileEditorInput) obj;
          if (index!=other.index)
              return false;
          return getFile().equals(other.getFile());
    }

    
    public int getIndex() {
    
        return index;
    }

}
