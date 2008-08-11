package net.bioclipse.ui.contenttypes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Hashtable;

import org.eclipse.core.internal.content.ContentMessages;
import org.eclipse.core.internal.content.TextContentDescriber;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExecutableExtension;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.osgi.util.NLS;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * @author maclean
 *
 */
@SuppressWarnings("restriction")
public class CmlFileDescriber extends TextContentDescriber 
							implements IExecutableExtension {
	
	private Hashtable elements = null;
	
	public final static String NS_CML = "http://www.xml-cml.org/schema";

	/**
	 * Store parameters
	 */
	public void setInitializationData(final IConfigurationElement config, final String propertyName, final Object data) throws CoreException {
		if (data instanceof String) {
			// why would this happen?
		} else if (data instanceof Hashtable) {
			elements = (Hashtable) data;
		}
		
		if (elements == null) {
			String message = NLS.bind(ContentMessages.content_badInitializationData, CmlFileCoordinatesDescriber.class.getName());
			throw new CoreException(new Status(IStatus.ERROR, ContentMessages.OWNER_NAME, 0, message, null));
		}
	}
	
	/**
	 * Determine what the CML file contains by quickly scanning the InputStream.
	 */
	public int describe(InputStream contents, IContentDescription description) throws IOException {
		return analyse(new InputStreamReader(contents), description);
	}
	
	/**
	 * Determine what the CML file contains by quickly scanning the Reader.
	 */
	public int describe(Reader contents, IContentDescription description) throws IOException {
		return analyse(contents, description);
	}

	/**
	 * Scan the document, looking for certain key features.
	 */
	private int analyse(Reader input, IContentDescription description) throws IOException {
		/*
		 * Check the CML for molecule tags, and check to see if it is 2D or 3D.
		 */
		boolean has2D = false;
		boolean has3D = false;
		boolean searchingForDimension = true;
		boolean checkedNamespace = false;
		int moleculeCount = 0;

		try {
			XmlPullParserFactory factory = 
				XmlPullParserFactory.newInstance(
						System.getProperty(XmlPullParserFactory.PROPERTY_NAME), null);
			factory.setNamespaceAware(true);
			factory.setValidating(false);

			XmlPullParser parser = factory.newPullParser();
			parser.setInput(input);
			while (parser.next() != XmlPullParser.END_DOCUMENT) {
				if (parser.getEventType() == XmlPullParser.START_TAG) {
				    String tagName = parser.getName();
				    
				    if (!checkedNamespace && tagName.equalsIgnoreCase("cml")) {
				        if (parser.getNamespace().equals(CmlFileDescriber.NS_CML)) {
				            checkedNamespace = true;
				        } else {
				            System.err.println("namespace = " + parser.getNamespace() + " INVALID");
				            return INVALID;
				        }
				    }
				    
					if (tagName.equalsIgnoreCase("molecule")) {
						moleculeCount++;
					}

					/*
					 * Search for the first example of an 'x2' or 'x3'
					 * attribute to be found in an 'atom' tag. This means
					 * that a mixed 2D/3D file will not be seen as such. 
					 */
					if (searchingForDimension && tagName.equalsIgnoreCase("atom")) {
						if (parser.getAttributeValue(null, "x2") != null) {
							has2D = true;
							searchingForDimension = false;
							break;
						}
						if (parser.getAttributeValue(null, "x3") != null) {
							has3D = true;
							searchingForDimension = false;
							break;
						}
					}
				}
			}
		} catch (XmlPullParserException xppe) {
			String message = "Error in CML file : line " + xppe.getLineNumber();
			System.err.println(message);
			throw new IOException(message);
		}


		/*
		 * Compare what was found with what the Describer expects. 
		 */

		String requiredDimension = (String) elements.get("dimension");
		boolean wants2D = requiredDimension.equalsIgnoreCase("2D");
		boolean wants3D = requiredDimension.equalsIgnoreCase("3D");

		String requiredCardinality = (String) elements.get("cardinality");
		boolean wantsSingle = requiredCardinality.equalsIgnoreCase("single");
		boolean wantsMultiple = requiredCardinality.equalsIgnoreCase("multiple");

		if ((has2D && wants2D) && (moleculeCount == 1 && wantsSingle)) {
			return VALID;
		}

		if ((has2D && wants2D) && (moleculeCount > 1 && wantsMultiple)) {
			return VALID;
		}

		if ((has3D && wants3D) && (moleculeCount == 1 && wantsSingle)) {
			return VALID;
		}

		if ((has3D && wants3D) && (moleculeCount > 1 && wantsMultiple)) {
			return VALID;
		}

		//Else, invalid (or indeterminate?)
		return INVALID;
	}
}
