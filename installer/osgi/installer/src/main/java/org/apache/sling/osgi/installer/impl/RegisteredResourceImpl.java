/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.osgi.installer.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.apache.felix.cm.file.ConfigurationHandler;
import org.apache.sling.osgi.installer.InstallableResource;
import org.apache.sling.osgi.installer.impl.config.ConfigurationPid;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;

/**
 * Implementation of the registered resource
 */
public class RegisteredResourceImpl
    implements RegisteredResource, Serializable {

    private static final String ENTITY_BUNDLE_PREFIX = "bundle:";
    private static final String ENTITY_CONFIG_PREFIX = "config:";

    private static final long serialVersionUID = 3L;
    private final String id;
	private final String urlScheme;
	private final String digest;
	private final String entity;
	private final Dictionary<String, Object> dictionary;
	private final Map<String, Object> attributes = new HashMap<String, Object>();
	private boolean installable = true;
	private final File dataFile;
	private final int priority;
    private final long serialNumber;
    private static long serialNumberCounter = System.currentTimeMillis();

    private final String resourceType;

    /**
     * Try to create a registered resource.
     */
    public static RegisteredResourceImpl create(final BundleContext ctx,
            final InstallableResource input,
            final String scheme) throws IOException {
        // installable resource has an id, a priority and either
        // an input stream or a dictionary
        InputStream is = input.getInputStream();
        Dictionary<String, Object> dict = input.getDictionary();
        String type = input.getType();
        if ( is == null ) {
            // if input stream is null, config through dictionary is expected!
            type = (type != null ? type : InstallableResource.TYPE_CONFIG);
        }
        final String resourceType = (type != null ? type : computeResourceType(getExtension(input.getId())));
        if ( resourceType == null ) {
            // unknown resource type
            throw new IOException("Unknown resource type for resource " + input.getId());
        }
        if ( !resourceType.equals(InstallableResource.TYPE_CONFIG) && !resourceType.equals(InstallableResource.TYPE_BUNDLE) ) {
            throw new IOException("Unsupported resource type " + resourceType + " for resource " + input.getId());
        }
        if ( is != null && resourceType.equals(InstallableResource.TYPE_CONFIG ) ) {
            dict = readDictionary(is, getExtension(input.getId()));
            if ( dict == null ) {
                throw new IOException("Unable to read dictionary from input stream: " + input.getId());
            }
            is = null;
        }

        return new RegisteredResourceImpl(ctx,
                input.getId(),
                is,
                dict,
                resourceType,
                input.getDigest(),
                input.getPriority(),
                scheme);
    }

	/**
	 * Create a RegisteredResource from given data.
	 * As this data object is filled from an {@link #create(BundleContext, InstallableResource, String)}
	 * we don't have to validate values - this has already been done
	 * The only exception is the digest!
	 */
	private RegisteredResourceImpl(final BundleContext ctx,
	        final String id,
	        final InputStream is,
	        final Dictionary<String, Object> dict,
	        final String type,
	        final String digest,
	        final int priority,
	        final String scheme) throws IOException {
        this.id = id;
        this.urlScheme = scheme;
		this.resourceType = type;
		this.priority = priority;
        this.dictionary = copy(dict);
		this.serialNumber = getNextSerialNumber();

		if (resourceType.equals(InstallableResource.TYPE_BUNDLE)) {
            try {
                this.dataFile = getDataFile(ctx);
                Logger.logDebug("Copying data to local storage " + this.dataFile);
                copyToLocalStorage(is);
                setAttributesFromManifest();
                final String name = (String)attributes.get(Constants.BUNDLE_SYMBOLICNAME);
                if (name == null) {
                    // not a bundle
                    throw new IOException("Bundle resource does not contain a bundle " + this.urlScheme + ":" + this.id);
                }
                this.digest = (digest != null && digest.length() > 0 ? digest : id + ":" + computeDigest(this.dataFile));
                entity = ENTITY_BUNDLE_PREFIX + name;
            } finally {
                is.close();
            }
		} else if ( resourceType.equals(InstallableResource.TYPE_CONFIG)) {
            this.dataFile = null;
            final ConfigurationPid pid = new ConfigurationPid(scheme + ':' + id);
            entity = ENTITY_CONFIG_PREFIX + pid.getCompositePid();
            attributes.put(CONFIG_PID_ATTRIBUTE, pid);
            this.digest = (digest != null && digest.length() > 0 ? digest : id + ":" + computeDigest(dict));
		} else {
		    throw new IOException("Unknown type " + resourceType);
		}
	}

    private static long getNextSerialNumber() {
        synchronized (RegisteredResourceImpl.class) {
            return serialNumberCounter++;
        }
    }

	@Override
	public String toString() {
	    return getClass().getSimpleName() + " " + this.getURL() + ", digest=" + this.getDigest() + ", serialNumber=" + this.getSerialNumber();
	}

	protected File getDataFile(final BundleContext bundleContext) {
		final String filename = getClass().getSimpleName() + "." + serialNumber;
		return bundleContext.getDataFile(filename);
	}

	/**
	 * @see org.apache.sling.osgi.installer.impl.RegisteredResource#cleanup()
	 */
	public void cleanup() {
	    if ( this.dataFile != null && this.dataFile.exists() ) {
		    Logger.logDebug("Deleting local storage file "
		                + dataFile.getAbsolutePath());
			dataFile.delete();
		}
	}

	/**
	 * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getURL()
	 */
	public String getURL() {
		return this.getScheme() + ':' + this.getId();
	}

	/**
	 * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getInputStream()
	 */
	public InputStream getInputStream() throws IOException {
	    if (this.dataFile != null && this.dataFile.exists() ) {
	        return new BufferedInputStream(new FileInputStream(this.dataFile));
	    }
        return  null;
	}

	/**
	 * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getDictionary()
	 */
	public Dictionary<String, Object> getDictionary() {
		return dictionary;
	}

	/**
	 * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getDigest()
	 */
	public String getDigest() {
		return digest;
	}

    /**
     * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getSerialNumber()
     */
    public long getSerialNumber() {
        return this.serialNumber;
    }

    /** Copy data to local storage */
	private void copyToLocalStorage(final InputStream data) throws IOException {
		final OutputStream os = new BufferedOutputStream(new FileOutputStream(this.dataFile));
		try {
			final byte[] buffer = new byte[16384];
			int count = 0;
			while( (count = data.read(buffer, 0, buffer.length)) > 0) {
				os.write(buffer, 0, count);
			}
			os.flush();
		} finally {
			os.close();
		}
	}

	/** Copy given Dictionary */
	private Dictionary<String, Object> copy(final Dictionary<String, Object> d) {
	    if ( d == null ) {
	        return null;
	    }
	    final Dictionary<String, Object> result = new Hashtable<String, Object>();
	    final Enumeration<String> e = d.keys();
	    while(e.hasMoreElements()) {
	        final String key = e.nextElement();
            result.put(key, d.get(key));
	    }
	    return result;
	}

	/**
	 * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getId()
	 */
	public String getId() {
	    return id;
	}

    /**
     * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getType()
     */
    public String getType() {
        return resourceType;
    }

    /**
     * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getEntityId()
     */
    public String getEntityId() {
        return entity;
    }

    /**
     * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getAttributes()
     */
    public Map<String, Object> getAttributes() {
		return attributes;
	}

	/**
	 * @see org.apache.sling.osgi.installer.impl.RegisteredResource#isInstallable()
	 */
	public boolean isInstallable() {
        return installable;
	}

    /**
     * @see org.apache.sling.osgi.installer.impl.RegisteredResource#setInstallable(boolean)
     */
    public void setInstallable(boolean installable) {
        this.installable = installable;
    }

    /** Read the manifest from supplied input stream, which is closed before return */
    private Manifest getManifest(InputStream ins) throws IOException {
        Manifest result = null;

        JarInputStream jis = null;
        try {
            jis = new JarInputStream(ins);
            result= jis.getManifest();

        } finally {

            // close the jar stream or the inputstream, if the jar
            // stream is set, we don't need to close the input stream
            // since closing the jar stream closes the input stream
            if (jis != null) {
                try {
                    jis.close();
                } catch (IOException ignore) {
                }
            } else {
                try {
                    ins.close();
                } catch (IOException ignore) {
                }
            }
        }

        return result;
    }

    private void setAttributesFromManifest() throws IOException {
    	final Manifest m = getManifest(getInputStream());
    	if(m == null) {
            throw new IOException("Cannot get manifest of bundle resource");
    	}

    	final String sn = m.getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME);
        if(sn == null) {
            throw new IOException("Manifest does not supply " + Constants.BUNDLE_SYMBOLICNAME);
        }

    	final String v = m.getMainAttributes().getValue(Constants.BUNDLE_VERSION);
        if(v == null) {
            throw new IOException("Manifest does not supply " + Constants.BUNDLE_VERSION);
        }

        attributes.put(Constants.BUNDLE_SYMBOLICNAME, sn);
        attributes.put(Constants.BUNDLE_VERSION, v.toString());
    }

    /**
     * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getScheme()
     */
    public String getScheme() {
        return urlScheme;
    }

    /**
     * @see org.apache.sling.osgi.installer.impl.RegisteredResource#getPriority()
     */
    public int getPriority() {
        return priority;
    }

    /**
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    public int compareTo(final RegisteredResource b) {
        return compare(this, b);
    }

    public static int compare(final RegisteredResource a, final RegisteredResource b) {
        final boolean aBundle = a.getType().equals(InstallableResource.TYPE_BUNDLE);
        final boolean bBundle = b.getType().equals(InstallableResource.TYPE_BUNDLE);

        if (aBundle && bBundle) {
            return compareBundles(a, b);
        } else if (!aBundle && !bBundle){
            return compareConfig(a, b);
        } else if (aBundle) {
            return 1;
        } else {
            return -1;
        }
    }

    private static int compareBundles(final RegisteredResource a, final RegisteredResource b) {
        boolean isSnapshot = false;
        int result = 0;

        // Order first by symbolic name
        final String nameA = (String)a.getAttributes().get(Constants.BUNDLE_SYMBOLICNAME);
        final String nameB = (String)b.getAttributes().get(Constants.BUNDLE_SYMBOLICNAME);
        if(nameA != null && nameB != null) {
            result = nameA.compareTo(nameB);
        }

        // Then by version
        if(result == 0) {
            final Version va = new Version((String)a.getAttributes().get(Constants.BUNDLE_VERSION));
            final Version vb = new Version((String)b.getAttributes().get(Constants.BUNDLE_VERSION));
            isSnapshot = va.toString().contains("SNAPSHOT");
            // higher version has more priority, must come first so invert comparison
            result = vb.compareTo(va);
        }

        // Then by priority, higher values first
        if(result == 0) {
            if(a.getPriority() < b.getPriority()) {
                result = 1;
            } else if(a.getPriority() > b.getPriority()) {
                result = -1;
            }
        }

        if(result == 0 && isSnapshot) {
            // For snapshots, compare serial numbers so that snapshots registered
            // later get priority
            if(a.getSerialNumber() < b.getSerialNumber()) {
                result = 1;
            } else if(a.getSerialNumber() > b.getSerialNumber()) {
                result = -1;
            }
        }

        return result;
    }

    private static int compareConfig(final RegisteredResource a, final RegisteredResource b) {
        int result = 0;

        // First compare by pid
        final ConfigurationPid pA = (ConfigurationPid)a.getAttributes().get(RegisteredResource.CONFIG_PID_ATTRIBUTE);
        final ConfigurationPid pB = (ConfigurationPid)b.getAttributes().get(RegisteredResource.CONFIG_PID_ATTRIBUTE);
        if(pA != null && pA.getCompositePid() != null && pB != null && pB.getCompositePid() != null) {
            result = pA.getCompositePid().compareTo(pB.getCompositePid());
        }

        // Then by priority, higher values first
        if(result == 0) {
            if(a.getPriority() < b.getPriority()) {
                result = 1;
            } else if( a.getPriority() > b.getPriority()) {
                result = -1;
            }
        }

        return result;
    }

    /**
     * Compute the extension
     */
    private static String getExtension(String url) {
        final int pos = url.lastIndexOf('.');
        return (pos < 0 ? "" : url.substring(pos+1));
    }

    /**
     * Compute the resource type
     */
    private static String computeResourceType(String extension) {
        if (extension.equals("jar")) {
            return InstallableResource.TYPE_BUNDLE;
        }
        if ( extension.equals("cfg")
             || extension.equals("config")
             || extension.equals("xml")
             || extension.equals("properties")) {
            return InstallableResource.TYPE_CONFIG;
        }
        return extension;
    }

    /** convert digest to readable string (http://www.javalobby.org/java/forums/t84420.html) */
    private static String digestToString(MessageDigest d) {
        final BigInteger bigInt = new BigInteger(1, d.digest());
        return new String(bigInt.toString(16));
    }

    /** Digest is needed to detect changes in data, and must not depend on dictionary ordering */
    private static String computeDigest(Dictionary<String, Object> data) {
        try {
            final MessageDigest d = MessageDigest.getInstance("MD5");
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final ObjectOutputStream oos = new ObjectOutputStream(bos);

            final SortedSet<String> sortedKeys = new TreeSet<String>();
            if(data != null) {
                for(Enumeration<String> e = data.keys(); e.hasMoreElements(); ) {
                    final String key = e.nextElement();
                    sortedKeys.add(key);
                }
            }
            for(String key : sortedKeys) {
                oos.writeObject(key);
                oos.writeObject(data.get(key));
            }

            bos.flush();
            d.update(bos.toByteArray());
            return digestToString(d);
        } catch (Exception ignore) {
            return data.toString();
        }
    }

    /** Digest is needed to detect changes in data */
    private static String computeDigest(final File data) throws IOException {
        try {
            final InputStream is = new FileInputStream(data);
            try {
                final MessageDigest d = MessageDigest.getInstance("MD5");

                final byte[] buffer = new byte[8192];
                int count = 0;
                while( (count = is.read(buffer, 0, buffer.length)) > 0) {
                    d.update(buffer, 0, count);
                }
                return digestToString(d);
            } finally {
                is.close();
            }
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception ignore) {
            return data.toString();
        }
    }

    /**
     * Read dictionary from an input stream.
     * We use the same logic as Apache Felix FileInstall here:
     * - *.cfg files are treated as property files
     * - *.config files are handled by the Apache Felix ConfigAdmin file reader
     * @param is
     * @param extension
     * @return
     * @throws IOException
     */
    private static Dictionary<String, Object> readDictionary(
            final InputStream is, final String extension) {
        final Hashtable<String, Object> ht = new Hashtable<String, Object>();
        final InputStream in = new BufferedInputStream(is);
        try {
            if ( !extension.equals("config") ) {
                final Properties p = new Properties();
                in.mark(1);
                boolean isXml = in.read() == '<';
                in.reset();
                if (isXml) {
                    p.loadFromXML(in);
                } else {
                    p.load(in);
                }
                final Enumeration<Object> i = p.keys();
                while ( i.hasMoreElements() ) {
                    final Object key = i.nextElement();
                    ht.put(key.toString(), p.get(key));
                }
            } else {
                @SuppressWarnings("unchecked")
                final Dictionary<String, Object> config = ConfigurationHandler.read(in);
                final Enumeration<String> i = config.keys();
                while ( i.hasMoreElements() ) {
                    final String key = i.nextElement();
                    ht.put(key, config.get(key));
                }
            }
        } catch ( IOException ignore ) {
            return null;
        } finally {
            try { in.close(); } catch (IOException ignore) {}
        }

        return ht;
    }
}