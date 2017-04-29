package gw.internal.gosu.parser.java.compiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTool;
import gw.fs.IResource;
import gw.lang.javac.InMemoryClassJavaFileObject;
import gw.lang.javac.IJavaParser;
import gw.lang.javac.StringJavaFileObject;
import gw.lang.reflect.TypeSystem;
import gw.lang.reflect.module.IModule;
import gw.util.Pair;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

/**
 * A tool for parsing and compiling Java source.
 *
 * A notable feature of this tool is its ability to compile Java sources that reference
 * and invoke Gosu types.  This feature enables bi-directional Java interop with Gosu.
 */
public class JavaParser implements IJavaParser
{
  private static final JavaParser INSTANCE = new JavaParser();
  public static JavaParser instance()
  {
    return INSTANCE;
  }

  private JavaCompiler _javac;
  private JavaFileManager _fileManager;
  private GosuJavaFileManager _gfm;

  private JavaParser()
  {
  }

  private void init()
  {
    if( _javac == null )
    {
      _javac = JavacTool.create();

      JavacJacker javacJacker = JavacJacker.instance();
      if( javacJacker != null )
      {
        // Share our existing Gosu file manager from Javac

        _fileManager = javacJacker.getJavaFileManager();
        _gfm = javacJacker.getGosuFileManager();
      }
      else
      {
        // Make a new Gosu file manager exclusively for this JavaParser

        _fileManager = _javac.getStandardFileManager( null, null, Charset.forName( "UTF-8" ) );

        try
        {
          IModule globalModule = TypeSystem.getGlobalModule();
          if( globalModule != null )
          {
            ((StandardJavaFileManager)_fileManager).setLocation( StandardLocation.SOURCE_PATH, globalModule.getSourcePath().stream().map( IResource::toJavaFile ).collect( Collectors.toList() ) );
            ((StandardJavaFileManager)_fileManager).setLocation( StandardLocation.CLASS_PATH, globalModule.getJavaClassPath().stream().map( IResource::toJavaFile ).collect( Collectors.toList() ) );
          }
          _gfm = new GosuJavaFileManager( _fileManager, false );
        }
        catch( IOException e )
        {
          throw new RuntimeException( e );
        }
      }
    }
  }

  public boolean parseType( String fqn, List<CompilationUnitTree> trees, DiagnosticCollector<JavaFileObject> errorHandler )
  {
    try
    {
      init();

      Pair<JavaFileObject, String> pair = findJavaSource( fqn );
      if( pair == null )
      {
        return false;
      }

      StringWriter errors = new StringWriter();
      JavaCompiler.CompilationTask task = _javac.getTask( errors, _fileManager, errorHandler, Collections.singletonList( "-proc:none" ), null, Collections.singletonList( pair.getFirst() ) );
      JavacTaskImpl javacTask = (JavacTaskImpl)task;
      Iterable<? extends CompilationUnitTree> iterable = javacTask.parse();
      for( CompilationUnitTree x : iterable )
      {
        trees.add( x );
      }
    }
    catch( Exception e )
    {
      return false;
    }
    return true;
  }

  public boolean parseText( String src, List<CompilationUnitTree> trees, Consumer<SourcePositions> sourcePositions, Consumer<DocTrees> docTrees, DiagnosticCollector<JavaFileObject> errorHandler )
  {
    try
    {
      init();

      ArrayList<JavaFileObject> javaStringObjects = new ArrayList<>();
      javaStringObjects.add( new StringJavaFileObject( "sample", src ) );
      StringWriter errors = new StringWriter();
      JavaCompiler.CompilationTask task = _javac.getTask( errors, _fileManager, errorHandler, Collections.singletonList( "-proc:none" ), null, javaStringObjects );
      JavacTaskImpl javacTask = (JavacTaskImpl)task;
      Iterable<? extends CompilationUnitTree> iterable = javacTask.parse();
      for( CompilationUnitTree x : iterable )
      {
        trees.add( x );
      }
      if( sourcePositions != null )
      {
        sourcePositions.accept( Trees.instance( javacTask ).getSourcePositions() );
      }
      if( docTrees != null )
      {
        docTrees.accept( DocTrees.instance( javacTask ) );
      }
    }
    catch( Exception e )
    {
      return false;
    }
    return true;
  }

  /**
   * Compiles specified Java class name.  Maintains cache between calls to this method, therefore subsequent calls to this
   * method will consult the cache and return the previously compiled class if cached.
   */
  public InMemoryClassJavaFileObject compile( String fqn, Iterable<String> options, DiagnosticCollector<JavaFileObject> errorHandler )
  {
    init();

    InMemoryClassJavaFileObject compiledClass = _gfm.findCompiledFile( fqn );
    if( compiledClass != null )
    {
      return compiledClass;
    }

    Pair<JavaFileObject, String> fileObj = findJavaSource( fqn );
    if( fileObj == null )
    {
      return null;
    }

    StringWriter errors = new StringWriter();
    JavaCompiler.CompilationTask compilationTask = _javac.getTask( errors, _gfm, errorHandler, options, null, Collections.singletonList( fileObj.getFirst() ) );
    compilationTask.call();
    return _gfm.findCompiledFile( fileObj.getSecond() );
  }

  /**
   * Compiles fresh, no caching.  Intended for use with parser feedback tooling e.g., a Java editor.
   */
  public InMemoryClassJavaFileObject compile( JavaFileObject jfo, String fqn, Iterable<String> options, DiagnosticCollector<JavaFileObject> errorHandler )
  {
    init();

    StringWriter errors = new StringWriter();
    JavaCompiler.CompilationTask compilationTask = _javac.getTask( errors, _gfm, errorHandler, options, null, Collections.singletonList( jfo ) );
    compilationTask.call();
    return _gfm.findCompiledFile( fqn );
  }

  /**
   * Compiles a collection of java source files, intended for use a command line compiler.
   */
  public Collection<InMemoryClassJavaFileObject> compile( Collection<JavaFileObject> files, Iterable<String> options, DiagnosticCollector<JavaFileObject> errorHandler )
  {
    init();

    StringWriter errors = new StringWriter();
    JavaCompiler.CompilationTask compilationTask = _javac.getTask( errors, _gfm, errorHandler, options, null, files );
    compilationTask.call();
    return _gfm.getCompiledFiles();
  }

  public Pair<JavaFileObject, String> findJavaSource( String fqn )
  {
    init();

    if( _gfm == null )
    {
      // short-circuit reentrancy during init()
      return null;
    }

    try
    {
      JavaFileObject fileObj = _gfm.getSourceFileForInput( StandardLocation.SOURCE_PATH, fqn, JavaFileObject.Kind.SOURCE );
      if( fileObj == null )
      {
        int iDot = fqn.lastIndexOf( '.' );
        if( iDot > 0 )
        {
          String enclosingFqn = fqn.substring( 0, iDot );
          return findJavaSource( enclosingFqn );
        }
        return null;
      }
      else
      {
        return new Pair<>( fileObj, fqn );
      }
    }
    catch( IOException e )
    {
      throw new RuntimeException( e );
    }
  }

  @Override
  public void clear()
  {
    _javac = null;
    try
    {
      if( _fileManager != null )
      {
        _fileManager.close();
      }
    }
    catch( IOException e )
    {
      throw new RuntimeException( e );
    }
  }
}
