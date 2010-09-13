package com.jetbrains.python;

import com.intellij.codeInsight.documentation.DocumentationManager;
import com.intellij.lang.documentation.QuickDocumentationProvider;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xml.util.XmlStringUtil;
import com.jetbrains.python.console.PydevConsoleRunner;
import com.jetbrains.python.console.PydevDocumentationProvider;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyCallExpressionHelper;
import com.jetbrains.python.psi.resolve.QualifiedResolveResult;
import com.jetbrains.python.psi.resolve.ResolveImportUtil;
import com.jetbrains.python.psi.resolve.RootVisitor;
import com.jetbrains.python.psi.types.PyClassType;
import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.psi.types.TypeEvalContext;
import com.jetbrains.python.toolbox.ChainIterable;
import com.jetbrains.python.toolbox.FP;
import com.jetbrains.python.toolbox.Maybe;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides quick docs for classes, methods, and functions.
 */
public class PythonDocumentationProvider extends QuickDocumentationProvider {

  @NonNls private static final String LINK_TYPE_CLASS = "#class#";
  @NonNls private static final String LINK_TYPE_PARENT = "#parent#";

  // provides ctrl+hover info
  public String getQuickNavigateInfo(final PsiElement element) {
    if (element instanceof PyFunction) {
      PyFunction func = (PyFunction)element;
      StringBuilder cat = new StringBuilder();
      PyClass cls = func.getContainingClass();
      if (cls != null) {
        String cls_name = cls.getName();
        cat.append("class ").append(cls_name).append("\n");
        // It would be nice to have class import info here, but we don't know the ctrl+hovered reference and context
      }
      return $(cat.toString()).add(describeDecorators(func, LSame2, ", ", LSame1)).add(describeFunction(func, LSame2, LSame1)).toString();
    }
    else if (element instanceof PyClass) {
      PyClass cls = (PyClass)element;
      return describeDecorators(cls, LSame2, ", ", LSame1).add(describeClass(cls, LSame2, false, false)).toString();
    }
    return null;
  }

  private final static @NonNls String BR = "<br>";

  private static @NonNls String combUp(@NonNls String what) {
    return XmlStringUtil.escapeString(what).replace("\n", BR).replace(" ", "&nbsp;");
  }

  /**
   * Creates a HTML description of function definition.
   * @param fun the function
   * @param deco_name_wrapper puts a tag around decorator name
   * @param deco_separator is added between decorators
   * @param func_name_wrapper puts a tag around the function name
   * @param escaper sanitizes values that come directly from doc string or code
   * @return chain of strings for further chaining
   */
  private static ChainIterable<String> describeFunction(
    PyFunction fun,
    FP.Lambda1<Iterable<String>, Iterable<String>> func_name_wrapper,
    FP.Lambda1<String, String> escaper
  ) {
    ChainIterable<String> cat = new ChainIterable<String>();
    final String name = fun.getName();
    cat.add("def ").addWith(func_name_wrapper, $(name));
    cat.add(escaper.apply(PyUtil.getReadableRepr(fun.getParameterList(), false)));
    if (!PyNames.INIT.equals(name)) {
      final PyType returnType = fun.getReturnType();
      cat.add(escaper.apply("\nInferred return type: "));
      if (returnType == null) cat.add("unknown");
      else cat.add(returnType.getName());
    }
    return cat;
  }

  private static ChainIterable<String> describeDecorators(
    PyDecoratable what, FP.Lambda1<Iterable<String>, Iterable<String>> deco_name_wrapper,
    String deco_separator, FP.Lambda1<String, String> escaper
  ) {
    ChainIterable<String> cat = new ChainIterable<String>();
    PyDecoratorList deco_list = what.getDecoratorList();
    if (deco_list != null) {
      for (PyDecorator deco : deco_list.getDecorators()) {
        cat.add(describeDeco(deco, deco_name_wrapper, escaper)).add(deco_separator); // can't easily pass describeDeco to map() %)
      }
    }
    return cat;
  }

  /**
   * Creates a HTML description of function definition.
   * @param cls the class
   * @param name_wrapper wrapper to render the name with
   * @param allow_html
   *@param link_own_name if true, add link to class's own name  @return cat for easy chaining
   */
  private static ChainIterable<String> describeClass(
    PyClass cls,
    FP.Lambda1<Iterable<String>, Iterable<String>> name_wrapper,
    boolean allow_html, boolean link_own_name
  ) {
    ChainIterable<String> cat = new ChainIterable<String>();
    final String name = cls.getName();
    cat.add("class ");
    if (allow_html && link_own_name) cat.addWith(LinkMyClass, $(name));
    else cat.addWith(name_wrapper, $(name));
    final PyExpression[] ancestors = cls.getSuperClassExpressions();
    if (ancestors.length > 0) {
      cat.add("(");
      boolean is_not_first = false;
      for (PyExpression parent : ancestors) {
        if (is_not_first) cat.add(", ");
        else is_not_first = true;
        final String parent_name = parent.getName();
        if (allow_html) cat.addWith(new LinkWrapper(LINK_TYPE_PARENT + parent_name), $(parent_name));
        else cat.add(parent_name);
      }
      cat.add(")");
    }
    return cat;
  }

  //
  private static Iterable<String> describeDeco(
    PyDecorator deco,
    final FP.Lambda1<Iterable<String>, Iterable<String>> name_wrapper, //  addWith in tags, if need be
    final FP.Lambda1<String, String> arg_wrapper   // add escaping, if need be
  ) {
    ChainIterable<String> cat = new ChainIterable<String>();
    cat.add("@").addWith(name_wrapper, $(PyUtil.getReadableRepr(deco.getCallee(), true)));
    if (deco.hasArgumentList()) {
      PyArgumentList arglist = deco.getArgumentList();
      if (arglist != null) {
        cat
          .add("(")
          .add(interleave(FP.map(FP.combine(LReadableRepr, arg_wrapper), arglist.getArguments()), ", "))
          .add(")")
        ;
      }
    }
    return cat;
  }

  private static @NotNull ChainIterable<String> combUpDocString(@NotNull String docstring) {
    ChainIterable<String> cat = new ChainIterable<String>();
    // detect common indentation
    String[] fragments = Pattern.compile("\n").split(docstring);
    Pattern spaces_pat = Pattern.compile("^\\s+");
    boolean is_first = true;
    final int IMPOSSIBLY_BIG = 999999;
    int cut_width = IMPOSSIBLY_BIG;
    for (String frag : fragments) {
      if (frag.length() == 0) continue;
      int pad_width = 0;
      final Matcher matcher = spaces_pat.matcher(frag);
      if (matcher.find()) {
        pad_width = matcher.end();
        if (is_first) {
          is_first = false;
          if (pad_width == 0) continue; // first line may have zero padding // first line may have zero padding
        }
      }
      if (pad_width < cut_width) cut_width = pad_width;
    }
    // remove common indentation
    if (cut_width > 0 && cut_width < IMPOSSIBLY_BIG) {
      for (int i=0; i < fragments.length; i+= 1) {
        if (fragments[i].length() > 0) fragments[i] = fragments[i].substring(cut_width);
      }
    }
    // reconstruct back, dropping first empty fragment as needed
    is_first = true;
    for (String frag : fragments) {
      if (is_first && spaces_pat.matcher(frag).matches()) continue; // ignore all initial whitespace
      if (is_first) is_first = false;
      else cat.add(BR);
      cat.add(combUp(frag));
    }
    return cat;
  }

  // provides ctrl+Q doc
  public String generateDoc(PsiElement element, final PsiElement originalElement) {
    if (element != null && PydevConsoleRunner.isInPydevConsole(element) ||
      originalElement != null && PydevConsoleRunner.isInPydevConsole(originalElement)){
      return PydevDocumentationProvider.createDoc(element, originalElement);
    }
    ChainIterable<String> cat = new ChainIterable<String>(); // our main output sequence
    final ChainIterable<String> prolog_cat = new ChainIterable<String>(); // sequence for reassignment info, etc
    final ChainIterable<String> doc_cat = new ChainIterable<String>(); // sequence for doc string
    final ChainIterable<String> epilog_cat = new ChainIterable<String>(); // sequence for doc "copied from" notices and such

    cat.add(prolog_cat).addWith(TagCode, doc_cat).add(epilog_cat); // pre-assemble; then add stuff to individual cats as needed
    cat = wrapInTag("html", wrapInTag("body", cat));

    final ChainIterable<String> reassign_cat = new ChainIterable<String>(); // sequence for reassignment info, etc
    PsiElement followed = resolveToDocStringOwner(element, originalElement, reassign_cat);

    // check if we got a property ref.
    // if so, element is an accessor, and originalElement if an identifier
    // TODO: use messages from resources!
    PyClass cls;
    PsiElement outer = null;
    boolean is_property = false;
    String accessor_kind = "None";
    if (originalElement != null) {
      String element_name = originalElement.getText();
      if (PyUtil.isPythonIdentifier(element_name)) {
        outer = originalElement.getParent();
        if (outer instanceof PyQualifiedExpression) {
          PyExpression qual = ((PyQualifiedExpression)outer).getQualifier();
          if (qual != null) {
            PyType type = qual.getType(TypeEvalContext.fast());
            if (type instanceof PyClassType) {
              cls = ((PyClassType)type).getPyClass();
              if (cls != null) {
                Property property = cls.findProperty(element_name);
                if (property != null) {
                  is_property = true;
                  final AccessDirection dir = AccessDirection.of((PyElement)outer);
                  Maybe<PyFunction> accessor = property.getByDirection(dir);
                  prolog_cat
                    .add("property ").addWith(TagBold, $().addWith(TagCode, $(element_name)))
                    .add(" of ").add(describeClass(cls, TagCode, true, true))
                  ;
                  if (accessor.isDefined() && property.getDoc() != null) {
                    doc_cat.add(": ").add(property.getDoc()).add(BR);
                  }
                  else {
                    final PyFunction getter = property.getGetter().valueOrNull();
                    if (getter != null && getter != element) {
                      // not in getter, getter's doc comment may be useful
                      PyStringLiteralExpression docstring = getter.getDocStringExpression();
                      if (docstring != null) {
                        prolog_cat
                          .add(BR).addWith(TagItalic, $("Copied from getter:")).add(BR)
                          .add(docstring.getStringValue())
                        ;
                      }
                    }
                    doc_cat.add(BR);
                  }
                  doc_cat.add(BR);
                  if (accessor.isDefined() && accessor.value() == null) followed = null;
                  if (dir == AccessDirection.READ) accessor_kind = "Getter";
                  else if (dir == AccessDirection.WRITE) accessor_kind = "Setter";
                  else accessor_kind = "Deleter";
                  if (followed != null) epilog_cat.addWith(TagSmall, $(BR, BR, accessor_kind, " of property")).add(BR);
                }
              }
            }
          }
        }
      }
    }

    if (prolog_cat.isEmpty() && ! is_property) prolog_cat.add(reassign_cat);

    // now followed may contain a doc string  
    if (followed instanceof PyDocStringOwner) {
      String docString = null;
      PyStringLiteralExpression doc_expr = ((PyDocStringOwner) followed).getDocStringExpression();
      if (doc_expr != null) docString = doc_expr.getStringValue();
      // doc of what?
      if (followed instanceof PyClass) {
        cls = (PyClass)followed;
        doc_cat.add(describeDecorators(cls, TagItalic, BR, LCombUp));
        doc_cat.add(describeClass(cls, TagBold, true, false));
      }
      else if (followed instanceof PyFunction) {
        PyFunction fun = (PyFunction)followed;
        if (! is_property) {
          cls = fun.getContainingClass();
          if (cls != null) {
            doc_cat.addWith(TagSmall, describeClass(cls, TagCode, true, true)).add(BR).add(BR);
          }
        }
        else cls = null;
        doc_cat.add(describeDecorators(fun, TagItalic, BR, LCombUp)).add(describeFunction(fun, TagBold, LCombUp));
        if (docString == null) {
          addInheritedDocString(fun, cls, doc_cat, epilog_cat); 
        }
      }
      else if (followed instanceof PyFile) {
        // what to prepend to a module description?
        String path = VfsUtil.urlToPath(((PyFile)followed).getUrl());
        if ("".equals(path)) {
          prolog_cat.addWith(TagSmall, $(PyBundle.message("QDOC.module.path.unknown")));
        }
        else {
          RootFinder finder = new RootFinder(path);
          ResolveImportUtil.visitRoots(followed, finder);
          final String root_path = finder.getResult();
          if (root_path != null) {
            String after_part = path.substring(root_path.length());
            prolog_cat.addWith(TagSmall, $(root_path).addWith(TagBold, $(after_part)));
          }
          else prolog_cat.addWith(TagSmall, $(path));
        }
      }
      if (docString != null) {
        doc_cat.add(BR).add(combUpDocString(docString));
      }
    }
    else if (is_property) {
      // if it was a normal accessor, ti would be a function, handled by previous branch
      String accessor_message;
      if (followed != null) accessor_message = "Declaration: ";
      else accessor_message = accessor_kind + " is not defined.";
      doc_cat.addWith(TagItalic, $(accessor_message)).add(BR);
      if (followed != null) doc_cat.add(combUp(PyUtil.getReadableRepr(followed, false)));
    }
    else if (followed != null && outer instanceof PyReferenceExpression) {
      Class[] uninteresting_classes = {PyTargetExpression.class, PyAugAssignmentStatement.class};
      boolean is_interesting = element != null && ! PyUtil.instanceOf(element, uninteresting_classes);
      if (is_interesting) {
        PsiElement old_parent = element.getParent();
        is_interesting = ! PyUtil.instanceOf(old_parent, uninteresting_classes);
      }
      if (is_interesting) {
        doc_cat.add(combUp(PyUtil.getReadableRepr(followed, false)));
      }
    }
    if (doc_cat.isEmpty() && epilog_cat.isEmpty()) return null; // got nothing substantial to say!
    else return cat.toString();
  }

  private static class RootFinder implements RootVisitor {
    private String myResult;
    private String myPath;

    private RootFinder(String path) {
      myPath = path;
    }

    public boolean visitRoot(VirtualFile root) {
      String vpath = VfsUtil.urlToPath(root.getUrl());
      if (myPath.startsWith(vpath)) {
        myResult = vpath;
        return false;
      }
      else return true;
    }

    String getResult() {
      return myResult;
    }
  }

  @Nullable
  private static PsiElement resolveToDocStringOwner(PsiElement element, PsiElement originalElement, ChainIterable<String> prolog_cat) {
    // here the ^Q target is already resolved; the resolved element may point to intermediate assignments
    boolean reassignment_marked = false;
    if (element instanceof PyTargetExpression) {
      final String target_name = element.getText();
      if (! reassignment_marked) {
        //prolog_cat.add(TagSmall.apply($("Assigned to ", element.getText(), BR)));
        prolog_cat.addWith(TagSmall, $(PyBundle.message("QDOC.assigned.to.$0", target_name)).add(BR));
        reassignment_marked = true;
      }
      element = ((PyTargetExpression)element).findAssignedValue();
    }
    if (element instanceof PyReferenceExpression) {
      if (! reassignment_marked) {
        //prolog_cat.add(TagSmall.apply($("Assigned to ", element.getText(), BR)));
        prolog_cat.addWith(TagSmall, $(PyBundle.message("QDOC.assigned.to.$0", element.getText())).add(BR));
        reassignment_marked = true;
      }
      final QualifiedResolveResult resolveResult = ((PyReferenceExpression)element).followAssignmentsChain(TypeEvalContext.fast());
      element = resolveResult.isImplicit() ? null : resolveResult.getElement();
    }
    // it may be a call to a standard wrapper
    if (element instanceof PyCallExpression) {
      final PyCallExpression call = (PyCallExpression)element;
      Pair<String, PyFunction> wrap_info = PyCallExpressionHelper.interpretAsStaticmethodOrClassmethodWrappingCall(call, originalElement);
      if (wrap_info != null) {
        String wrapper_name = wrap_info.getFirst();
        PyFunction wrapped_func = wrap_info.getSecond();
        //prolog_cat.addWith(TagSmall, $("Wrapped in ").addWith(TagCode, $(wrapper_name)).add(BR));
        prolog_cat.addWith(TagSmall, $(PyBundle.message("QDOC.wrapped.in.$0", wrapper_name)).add(BR));
        element = wrapped_func;
      }
    }
    return element;
  }

  private static void addInheritedDocString(PyFunction fun, PyClass cls, ChainIterable<String> doc_cat, ChainIterable<String> epilog_cat) {
    boolean not_found = true;
    String meth_name = fun.getName();
    if (cls != null && meth_name != null) {
      final boolean is_constructor = PyNames.INIT.equals(meth_name);
      // look for inherited and its doc
      Iterable<PyClass> classes = cls.iterateAncestors();
      if (is_constructor) {
        // look at our own class again and maybe inherit class's doc 
        classes = new ChainIterable<PyClass>(cls).add(classes);
      }
      for (PyClass ancestor : classes) {
        PyStringLiteralExpression doc_elt = null;
        PyFunction inherited = null;
        boolean is_from_class = false;
        if (is_constructor) doc_elt = cls.getDocStringExpression();
        if (doc_elt != null) is_from_class = true;
        else inherited = ancestor.findMethodByName(meth_name, false);
        if (inherited != null) {
          doc_elt = inherited.getDocStringExpression();
        }
        if (doc_elt != null) {
          String inherited_doc = doc_elt.getStringValue();
          if (inherited_doc.length() > 1) {
            epilog_cat.add(BR).add(BR);
            String ancestor_name = ancestor.getName();
            String marker = (cls == ancestor)? LINK_TYPE_CLASS : LINK_TYPE_PARENT;
            final String ancestor_link = $().addWith(new LinkWrapper(marker + ancestor_name), $(ancestor_name)).toString();
            if (is_from_class) epilog_cat.add(PyBundle.message("QDOC.copied.from.class.$0", ancestor_link));
            else {
              epilog_cat.add(PyBundle.message("QDOC.copied.from.$0.$1", ancestor_link, meth_name));
            }
            epilog_cat
              .add(BR).add(BR)
              .addWith(TagCode, combUpDocString(inherited_doc))
            ;
            not_found = false;
            break;
          }
        }
      }

      if (not_found) {
        // above could have not worked because inheritance is not searched down to 'object'.
        // for well-known methods, copy built-in doc string.
        // TODO: also handle predefined __xxx__ that are not part of 'object'.
        if (PyNames.UnderscoredAttributes.contains(meth_name)) {
          PyClassType objtype = PyBuiltinCache.getInstance(fun).getObjectType(); // old- and new-style classes share the __xxx__ stuff
          if (objtype != null) {
            PyClass objcls = objtype.getPyClass();
            if (objcls != null) {
              PyFunction obj_underscored = objcls.findMethodByName(meth_name, false);
              if (obj_underscored != null) {
                PyStringLiteralExpression predefined_doc_expr = obj_underscored.getDocStringExpression();
                String predefined_doc = predefined_doc_expr != null? predefined_doc_expr.getStringValue() : null;
                if (predefined_doc != null && predefined_doc.length() > 1) { // only a real-looking doc string counts
                  doc_cat.add(combUpDocString(predefined_doc));
                  epilog_cat.add(BR).add(BR).add(PyBundle.message("QDOC.copied.from.builtin"));
                }
              }
            }
          }
        }
      }
    }
  }

  @Override
  public PsiElement getDocumentationElementForLink(PsiManager psiManager, String link, PsiElement context) {
    if (link.equals(LINK_TYPE_CLASS)) {
      return inferContainingClassOf(context);
    }
    else if (link.startsWith(LINK_TYPE_PARENT)) {
      PyClass cls = inferContainingClassOf(context);
      if (cls != null) {
        String desired_name = link.substring(LINK_TYPE_PARENT.length());
        for (PyClass parent : cls.iterateAncestors()) {
          final String parent_name = parent.getName();
          if (parent_name != null && parent_name.equals(desired_name)) return parent;
        }
      }
    }
    return null;
  }

  @Nullable
  private static PyClass inferContainingClassOf(PsiElement context) {
    if (context instanceof PyClass) return (PyClass)context;
    if (context instanceof PyFunction) return ((PyFunction)context).getContainingClass();
    else return PsiTreeUtil.getParentOfType(context, PyClass.class);
  }

  private static final FP.Lambda1<String, String> LCombUp = new FP.Lambda1<String, String>() {
    public String apply(String argname) {
      return combUp(argname);
    }
  };

  private static final FP.Lambda1<String, String> LSame1 = new FP.Lambda1<String, String>() {
    public String apply(String name) {
      return name;
    }
  };


  private static ChainIterable<String> wrapInTag(String tag, Iterable<String> content) {
    return new ChainIterable<String>("<" + tag + ">").add(content).add("</" + tag + ">");
  }

  private static ChainIterable<String> $(String... content) {
    return new ChainIterable<String>(Arrays.asList(content));
  }


  // make a first-order curried objects out of wrapInTag()
  private static class TagWrapper implements FP.Lambda1<Iterable<String>, Iterable<String>> {
    private final String myTag;

    TagWrapper(String tag) {
      myTag = tag;
    }

    public Iterable<String> apply(Iterable<String> contents) {
      return wrapInTag(myTag, contents);
    }

  }

  private static final TagWrapper TagBold = new TagWrapper("b");
  private static final TagWrapper TagItalic = new TagWrapper("i");
  private static final TagWrapper TagSmall = new TagWrapper("small");
  private static final TagWrapper TagCode = new TagWrapper("code");

  private static class LinkWrapper implements FP.Lambda1<Iterable<String>, Iterable<String>> {
    private String myLink;

    LinkWrapper(String link) {
      myLink = link;
    }

    public Iterable<String> apply(Iterable<String> contents) {
      return new ChainIterable<String>()
        .add("<a href=\"").add(DocumentationManager.PSI_ELEMENT_PROTOCOL).add(myLink).add("\">")
        .add(contents).add("</a>")
      ;
    }
  }

  private static final LinkWrapper LinkMyClass = new LinkWrapper(LINK_TYPE_CLASS); // link item to containing class
 

  private static final FP.Lambda1<Iterable<String>, Iterable<String>> LSame2 = new FP.Lambda1<Iterable<String>, Iterable<String>>() {
    public Iterable<String> apply(Iterable<String> what) {
      return what;
    }
  };

  public static FP.Lambda1<PyExpression, String> LReadableRepr = new FP.Lambda1<PyExpression, String>() {
    public String apply(PyExpression arg) {
      return PyUtil.getReadableRepr(arg, true);
    }
  };

  private static <T> Iterable<T> interleave(Iterable<T> source, T filler) {
    List<T> ret = new LinkedList<T>();
    boolean is_next = false;
    for (T what : source) {
      if (is_next) ret.add(filler);
      else is_next = true;
      ret.add(what);
    }
    return ret;
  }
}
