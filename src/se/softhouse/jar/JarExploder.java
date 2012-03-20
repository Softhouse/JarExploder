/*
 * Copyright (c) 2012, Petter Nordholm, Softhouse Consulting AB
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so:
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package se.softhouse.jar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * This class implements a simple self exploding jar file. The class is supposed to be
 * placed within a jar file and specified as the Main-Class within that jar. It will
 * find the jar it is contained within, uncompress that jar into a temporary folder and
 * then spawn a new main class. All jar files within that jar will be added to the 
 * class path.
 * 
 * Using this class, the application can easily be released as a single jar file and
 * started with java -jar <jarfilename> .
 * 
 *  Example jar content:
 *  
 *  <pre>
 *  explodingjar.jar
 *  + se/
 *   + softhouse/
 *    + jar/
 *     + JarExploder.class
 *  + lib/
 *   + mylibrary.jar
 *   + 3rdparty.jar
 *  </pre>
 *  
 *  In the manifest file:
 *  <pre>
 *  Main-Class: se.softhouse.jar.JarExploder
 *  Start-Class: se.softhouse.project.StartApplication
 *  </pre>
 *  
 *  Start the application with:
 *  <pre>
 *    java -jar explodingjar.jar
 *  </pre>
 *  
 *  To enable debugging, add -DDEBUG to the argument list before the -jar parameter:
 *  
 *  <pre>
 *    java -DDEBUG -jar explodingjar.jar
 *  </pre>
 *  
 *  The implementation of this class is kept in one class without any external dependencies to simplify deployment
 *  and building.
 *  
 *  Example usage is to construct the file in a war structure and add an extra main class which starts jetty. This way,
 *  the war file can be run stand-a-lone and as a war file on an application server.
 */
public class JarExploder
{
	// Temporary folder for all jar contents.
	private File myTempDir;

	public static void main(String[] args) throws Exception
	{
		new JarExploder().run(args);
	}

	private static StackTraceElement getCaller(int level)
	{
		return Thread.currentThread().getStackTrace()[1 + level];
	}

	/**
	 * Prints a debug message if debugging is enabled.
	 * @param format format message string a la' printf
	 * @param args the format arguments if any.
	 */
	private static void DEBUG(String format,Object... args)
	{
		if(isDebug())
		{
			LOG("DEBUG",format,args);
		}
	}
	
	/**
	 * Prints a info message
	 * @param format format message string a la' printf
	 * @param args the format arguments if any.
	 */
	private static void INFO(String format,Object... args)
	{
		LOG("INFO",format,args);
	}

	private static void LOG(String type, String format, Object[] args)
	{	
		StackTraceElement elem = getCaller(2);
		String message = String.format(format, args);
		System.err.println("[" + type +":" + elem.getClassName()+"@" + elem.getMethodName() + "] - " + message);
	}

	private static boolean isDebug()
	{
		return Boolean.getBoolean("DEBUG");
	}

	/**
	 * @return a file for a newly create temporary directory-
	 * @throws IOException
	 */
	private static File createTempDirectory() throws IOException
	{
		final File temp;

		temp = File.createTempFile("temp", Long.toString(System.nanoTime()));
		INFO("Temporary directory is: " + temp.getCanonicalPath());
		if (!(temp.delete()))
		{
			throw new IOException("Could not delete temp file: "
					+ temp.getAbsolutePath());
		}

		if (!(temp.mkdir()))
		{
			throw new IOException("Could not create temp directory: "
					+ temp.getAbsolutePath());
		}

		temp.deleteOnExit();
		DEBUG("Directory [%s] will be deleted on exit", temp.toString());
		return (temp);
	}

	private void run(String[] args) throws IOException, ClassNotFoundException, NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException, InvocationTargetException
	{
		List<URL> jarFiles = new ArrayList<>();
		
		String classpath = System.getProperty("java.class.path");
		DEBUG("Classpath is [%s]", classpath);
		
		myTempDir = createTempDirectory();

		// This file is always the first entry in the classpath
		JarFile jarFile = new JarFile(classpath.split(File.pathSeparator)[0]);
		Enumeration<JarEntry> jarEntries = jarFile.entries();
		while(jarEntries.hasMoreElements())
		{
			JarEntry jarEntry = jarEntries.nextElement();
			if (jarEntry.isDirectory())
			{
				INFO("Create directory [%s]", jarEntry.getName());
				File newDir = new File(myTempDir,jarEntry.getName());
				DEBUG("Directory [%s] will be deleted on exit", newDir.toString());
				newDir.mkdirs();
				newDir.deleteOnExit();
			}
			else
			{
				INFO("Saving file [%s]", jarEntry.getName());
				File jarEntryFile = new File(myTempDir,jarEntry.getName());
				jarEntryFile.deleteOnExit();
				DEBUG("File [%s] will be deleted on exit", jarEntryFile.toString());
				copyJarEntryToFile(jarFile.getInputStream(jarEntry),jarEntryFile);
				if (jarEntryFile.getName().endsWith(".jar"))
				{
					DEBUG("Jarfile [%s] added to classpath", jarEntryFile.getCanonicalPath());
					jarFiles.add(new URL("file","",jarEntryFile.getCanonicalPath()));
				}
			}
		}
		
		URLClassLoader classloader = new URLClassLoader(jarFiles.toArray(new URL[]{}));
		Attributes mainAttributes = jarFile.getManifest().getMainAttributes();
		String startClass = mainAttributes.getValue("Start-Class");
		if (startClass == null)
		{
			throw new IllegalArgumentException("Manifest must contain a Start-Class entry in the main part of the manifest");
		}
		INFO("Calling startup class [%s]", startClass);
		Class<?> klass = Class.forName(startClass, true, classloader);
		Method method = klass.getMethod("main", new Class<?>[]{args.getClass()});
		try
		{
			method.invoke(null, (Object)args);
			classloader = cleanupClassLoaderReferences(classloader);
			System.exit(0);
		}
		catch(Throwable t)
		{
			t.printStackTrace();
			classloader = cleanupClassLoaderReferences(classloader);
			System.exit(-1);
		}
	}

	private URLClassLoader cleanupClassLoaderReferences(URLClassLoader classloader) throws IOException
	{
		classloader.close();
		System.gc();
		return null;
	}

	/**
	 * Copies the contents of the given inputstream and stores it in the given file.
	 */
	private void copyJarEntryToFile(InputStream inputStream, File file) throws IOException
	{
		try(FileOutputStream fos = new FileOutputStream(file))
		{
			byte data[] = new byte[4096];
			int read = inputStream.read(data);
			while(read > -1)
			{
				fos.write(data, 0, read);
				read = inputStream.read(data);
			}
		}
	}
}
