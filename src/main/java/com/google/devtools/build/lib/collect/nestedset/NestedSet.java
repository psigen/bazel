// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.collect.nestedset;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet;
import com.google.devtools.build.lib.concurrent.MoreFutures;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * A NestedSet is an immutable ordered set of element values of type {@code E}. Elements must not be
 * arrays.
 *
 * <p>Conceptually, NestedSet values form a directed acyclic graph (DAG). Each leaf node represents
 * a set containing a single element; there is also a distinguished leaf node representing the empty
 * set. Each non-leaf node represents the union of the sets represented by its successors.
 *
 * <p>A NestedSet value represents a node in this graph. The elements of a NestedSet may be
 * enumerated by traversing the complete DAG, eliminating duplicates using an ephemeral hash table.
 * The {@link #toList} and {@link #toSet} methods provide the result of this traversal as a list or
 * a set, respectively. These operations, which are relatively expensive, are known as "flattening".
 * Computing the size of the set requires flattening.
 *
 * <p>By contrast, construction of a new set as a union of existing sets is relatively cheap. The
 * constructor accepts a list of "direct" elements and list of "transitive" nodes. The resulting
 * NestedSet refers to a new graph node representing their union. The relative order of direct and
 * transitive successors is governed by the Order parameter. Duplicates among the "direct" elements
 * are eliminated at construction, again with an ephemeral hash table. If after duplicate
 * elimination the new node would have exactly one successor, whether "direct" or "transitive", the
 * resulting NestedSet reuses the existing node for the sole successor.
 *
 * <p>The implementation has been highly optimized as it is crucial to Blaze's performance.
 *
 * @see NestedSetBuilder
 */
@SuppressWarnings("unchecked")
@AutoCodec
public final class NestedSet<E> {
  /**
   * Order and size of set packed into one int.
   *
   * <p>Bits 31-2: size, bits 1-0: order enum ordinal. The order is assigned on construction time,
   * the size is computed on the first expansion and set afterwards so it's available for {@link
   * #replay}.
   */
  private int orderAndSize;

  // children contains the "direct" elements and "transitive" nested sets.
  // Direct elements are never arrays.
  // Transitive elements may be arrays, but singletons are replaced by their sole element
  // (thus transitive arrays always contain at least two logical elements).
  // The relative order of direct and transitive is determined by the Order.
  // All empty sets have children==EMPTY_CHILDREN, not null.
  //
  // Please be careful to use the terms of the conceptual model in the API documentation,
  // and the terms of the physical representation in internal comments. They are not the same.
  //
  // TODO(adonovan): rename this field and related accessors to something less suggestive,
  // such as 'state' or 'repr' or 'node'.  The public API no longer mentions "children".
  final Object children;

  @Nullable private byte[] memo;

  /**
   * The application depth limit of nested sets. Nested sets over this depth will throw {@link
   * NestedSetDepthException} on flattening of the depset.
   *
   * <p>This limit should be set by command line option processing in the Bazel server.
   */
  private static final AtomicInteger expansionDepthLimit = new AtomicInteger(3500);

  private static final byte[] LEAF_MEMO = {};
  @AutoCodec static final Object[] EMPTY_CHILDREN = {};

  /** Construct an empty NestedSet. Should only be called by Order's class initializer. */
  NestedSet(Order order) {
    this.orderAndSize = order.ordinal();
    this.children = EMPTY_CHILDREN;
    this.memo = LEAF_MEMO;
  }

  NestedSet(
      Order order, Set<E> direct, Set<NestedSet<E>> transitive, InterruptStrategy interruptStrategy)
      throws InterruptedException {
    this.orderAndSize = order.ordinal();

    // The iteration order of these collections is the order in which we add the items.
    Collection<E> directOrder = direct;
    Collection<NestedSet<E>> transitiveOrder = transitive;
    // True if we visit the direct members before the transitive members.
    boolean preorder;

    switch(order) {
      case LINK_ORDER:
        directOrder = ImmutableList.copyOf(direct).reverse();
        transitiveOrder = ImmutableList.copyOf(transitive).reverse();
        preorder = false;
        break;
      case STABLE_ORDER:
      case COMPILE_ORDER:
        preorder = false;
        break;
      case NAIVE_LINK_ORDER:
        preorder = true;
        break;
      default:
        throw new AssertionError(order);
    }

    // Remember children we extracted from one-element subsets. Otherwise we can end up with two of
    // the same child, which is a problem for the fast path in toList().
    Set<E> alreadyInserted = ImmutableSet.of();
    // The candidate array of children.
    Object[] children = new Object[direct.size() + transitive.size()];
    int n = 0;  // current position in children
    boolean leaf = true;  // until we find otherwise

    for (int pass = 0; pass <= 1; ++pass) {
      if ((pass == 0) == preorder && !direct.isEmpty()) {
        for (E member : directOrder) {
          if (member instanceof Object[]) {
            throw new IllegalArgumentException("cannot store Object[] in NestedSet");
          }
          if (member instanceof ByteString) {
            throw new IllegalArgumentException("cannot store ByteString in NestedSet");
          }
          if (!alreadyInserted.contains(member)) {
            children[n++] = member;
          }
        }
        alreadyInserted = direct;
      } else if ((pass == 1) == preorder && !transitive.isEmpty()) {
        CompactHashSet<E> hoisted = null;
        for (NestedSet<E> subset : transitiveOrder) {
          // If this is a deserialization future, this call blocks.
          Object c = subset.getChildrenInternal(interruptStrategy);
          if (c instanceof Object[]) {
            Object[] a = (Object[]) c;
            if (a.length < 2) {
              throw new AssertionError(a.length);
            }
            children[n++] = a;
            leaf = false;
          } else {
            if (!alreadyInserted.contains(c)) {
              if (hoisted == null) {
                hoisted = CompactHashSet.create();
              }
              if (hoisted.add((E) c)) {
                children[n++] = c;
              }
            }
          }
        }
        alreadyInserted = hoisted == null ? ImmutableSet.of() : hoisted;
      }
    }

    // If we ended up wrapping exactly one item or one other set, dereference it.
    if (n == 1) {
      this.children = children[0];
    } else if (n == 0) {
      this.children = EMPTY_CHILDREN;
    } else if (n < children.length) {
      this.children = Arrays.copyOf(children, n);
    } else {
      this.children = children;
    }
    if (leaf) {
      this.memo = LEAF_MEMO;
    }
  }

  private NestedSet(Order order, Object children, @Nullable byte[] memo) {
    this.orderAndSize = order.ordinal();
    this.children =
        children instanceof Object[] && ((Object[]) children).length == 0
            ? EMPTY_CHILDREN
            : children;
    this.memo = memo;
  }

  /**
   * Constructs a NestedSet that is currently being deserialized. The provided future, when
   * complete, gives the contents of the NestedSet.
   */
  static <E> NestedSet<E> withFuture(
      Order order, ListenableFuture<Object[]> deserializationFuture) {
    return new NestedSet<>(order, deserializationFuture, /*memo=*/ null);
  }

  // Only used by deserialization
  @AutoCodec.Instantiator
  static <E> NestedSet<E> forDeserialization(Order order, Object children) {
    Preconditions.checkState(!(children instanceof ListenableFuture));
    boolean hasChildren =
        children instanceof Object[]
            && (Arrays.stream((Object[]) children).anyMatch(child -> child instanceof Object[]));
    byte[] memo = hasChildren ? null : LEAF_MEMO;
    return new NestedSet<>(order, children, memo);
  }

  /** Returns the ordering of this nested set. */
  public Order getOrder() {
    return Order.getOrder(orderAndSize & 3);
  }

  /**
   * Returns the internal item or array. If the internal item is a deserialization future, blocks on
   * completion. For use only by NestedSetVisitor.
   */
  Object getChildren() {
    return getChildrenUninterruptibly();
  }

  /** Same as {@link #getChildren}, except propagates {@link InterruptedException}. */
  Object getChildrenInterruptibly() throws InterruptedException {
    return children instanceof ListenableFuture
        ? MoreFutures.waitForFutureAndGet((ListenableFuture<Object[]>) children)
        : children;
  }

  /**
   * What to do when an interruption occurs while getting the result of a deserialization future.
   */
  enum InterruptStrategy {
    /** Crash with {@link ExitCode#INTERRUPTED}. */
    CRASH,
    /** Throw {@link InterruptedException}. */
    PROPAGATE
  }

  /** Implementation of {@link #getChildren} that will catch an InterruptedException and crash. */
  private Object getChildrenUninterruptibly() {
    if (children instanceof ListenableFuture) {
      try {
        return MoreFutures.waitForFutureAndGet((ListenableFuture<Object[]>) children);
      } catch (InterruptedException e) {
        System.err.println(
            "An interrupted exception occurred during nested set deserialization, "
                + "exiting abruptly.");
        BugReport.handleCrash(e, ExitCode.INTERRUPTED);
        throw new IllegalStateException("Server should have shut down.", e);
      }
    } else {
      return children;
    }
  }

  /**
   * Private implementation of getChildren that will propagate an InterruptedException from a future
   * in the nested set based on the value of {@code interruptStrategy}.
   */
  private Object getChildrenInternal(InterruptStrategy interruptStrategy)
      throws InterruptedException {
    switch (interruptStrategy) {
      case CRASH:
        return getChildrenUninterruptibly();
      case PROPAGATE:
        return getChildrenInterruptibly();
    }
    throw new IllegalStateException("Unknown interrupt strategy " + interruptStrategy);
  }

  /**
   * forEachElement applies function {@code f} to each element of the NestedSet.
   *
   * <p>The {@code descend} function is called for each node in the DAG, and if it returns false,
   * the traversal is pruned and does not descend into that node; if the node was a leaf, {@code f}
   * is not called.
   *
   * <p>Clients must treat the {@code descend} function's argument as an opaque reference: only
   * {@link System#identityHashCode} and {@code ==} should be applied to it.
   */
  // TODO(b/157992832): this function is an encapsulation-breaking hack for the function named in
  // the bug report. Eliminate it, and make it use NestedSetVisitor instead.
  public void forEachElement(Predicate<Object> descend, Consumer<E> f) {
    forEachElementImpl(descend, f, getChildren());
  }

  private static <E> void forEachElementImpl(
      Predicate<Object> descend, Consumer<E> f, Object node) {
    if (descend.test(node)) {
      if (node instanceof Object[]) {
        for (Object child : (Object[]) node) {
          forEachElementImpl(descend, f, child);
        }
      } else {
        @SuppressWarnings("unchecked")
        E elem = (E) node;
        f.accept(elem);
      }
    }
  }

  /** Returns true if the set is empty. Runs in O(1) time (i.e. does not flatten the set). */
  public boolean isEmpty() {
    // We don't check for future members here, since empty sets are special-cased in serialization
    // and do not make requests against storage.
    return children == EMPTY_CHILDREN;
  }

  /** Returns true if the set has exactly one element. */
  public boolean isSingleton() {
    return isSingleton(children);
  }

  private static boolean isSingleton(Object children) {
    // Singleton sets are special cased in serialization, and make no calls to storage.  Therefore,
    // we know that any NestedSet with a ListenableFuture member is not a singleton.
    return !(children instanceof Object[] || children instanceof ListenableFuture);
  }

  /** Returns true if this set depends on data from storage. */
  public boolean isFromStorage() {
    return children instanceof ListenableFuture;
  }

  /**
   * Returns true if the contents of this set are currently available in memory.
   *
   * <p>Only returns false if this set {@link #isFromStorage} and the contents are not fully
   * deserialized.
   */
  public boolean isReady() {
    return !isFromStorage() || ((ListenableFuture<Object[]>) children).isDone();
  }

  /** Returns the single element; only call this if {@link #isSingleton} returns true. */
  public E getSingleton() {
    Preconditions.checkState(isSingleton());
    return (E) children;
  }

  /**
   * Returns an immutable list of all unique elements of the this set, similar to {@link #toList},
   * but will propagate an {@code InterruptedException} if one is thrown.
   */
  public ImmutableList<E> toListInterruptibly() throws InterruptedException {
    return actualChildrenToList(getChildrenInterruptibly());
  }

  /**
   * Returns an immutable list of all unique elements of the this set, similar to {@link #toList},
   * but will propagate an {@code InterruptedException} if one is thrown and will throw {@link
   * TimeoutException} if this set is deserializing and does not become ready within the given
   * timeout.
   *
   * <p>Note that the timeout only applies to blocking for the deserialization future to become
   * available. The actual list transformation is untimed.
   */
  public ImmutableList<E> toListWithTimeout(Duration timeout)
      throws InterruptedException, TimeoutException {
    Object actualChildren;
    if (children instanceof ListenableFuture) {
      try {
        actualChildren =
            ((ListenableFuture<Object[]>) children).get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      } catch (ExecutionException e) {
        Throwables.propagateIfPossible(e.getCause(), InterruptedException.class);
        throw new IllegalStateException(e);
      }
    } else {
      actualChildren = children;
    }
    return actualChildrenToList(actualChildren);
  }

  /**
   * Returns an immutable list of all unique elements of this set (including subsets) in an
   * implementation-specified order.
   *
   * <p>Prefer calling this method over {@link ImmutableList#copyOf} on this set for better
   * efficiency, as it saves an iteration.
   */
  public ImmutableList<E> toList() {
    return actualChildrenToList(getChildrenUninterruptibly());
  }

  /**
   * Private implementation of toList which takes the actual children (the deserialized {@code
   * Object[]} if {@link #children} is a {@link ListenableFuture}).
   */
  private ImmutableList<E> actualChildrenToList(Object actualChildren) {
    if (actualChildren == EMPTY_CHILDREN) {
      return ImmutableList.of();
    }
    if (!(actualChildren instanceof Object[])) {
      return ImmutableList.of((E) actualChildren);
    }
    ImmutableList<E> list = expand((Object[]) actualChildren);
    return getOrder() == Order.LINK_ORDER ? list.reverse() : list;
  }

  /**
   * Returns an immutable set of all unique elements of this set (including subsets) in an
   * implementation-specified order.
   */
  public ImmutableSet<E> toSet() {
    return ImmutableSet.copyOf(toList());
  }

  /**
   * Important: This does a full traversal of the nested set if it's not been previously traversed.
   *
   * @return the size of the nested set.
   */
  public int memoizedFlattenAndGetSize() {
    if (orderAndSize >> 2 == 0) {
      // toList() only implicitly updates orderAndSize if this is a NestedSet with transitives.
      // Therefore we need to explicitly set it here.
      orderAndSize |= toList().size() << 2;
    }
    return orderAndSize >> 2;
  }

  /**
   * Returns true if this set is equal to {@code other} based on the top-level elements and object
   * identity (==) of direct subsets. As such, this function can fail to equate {@code this} with
   * another {@code NestedSet} that holds the same elements. It will never fail to detect that two
   * {@code NestedSet}s are different, however.
   *
   * <p>If one of the sets is in the process of deserialization, returns true iff both sets depend
   * on the same future.
   *
   * @param other the {@code NestedSet} to compare against.
   */
  public boolean shallowEquals(@Nullable NestedSet<? extends E> other) {
    if (this == other) {
      return true;
    }

    return other != null
        && getOrder() == other.getOrder()
        && (children.equals(other.children)
            || (!isSingleton()
                && !other.isSingleton()
                && children instanceof Object[]
                && other.children instanceof Object[]
                && Arrays.equals((Object[]) children, (Object[]) other.children)));
  }

  /**
   * Returns a hash code that produces a notion of identity that is consistent with {@link
   * #shallowEquals}. In other words, if two {@code NestedSet}s are equal according to {@code
   * #shallowEquals}, then they return the same {@code shallowHashCode}.
   *
   * <p>The main reason for having these separate functions instead of reusing the standard
   * equals/hashCode is to minimize accidental use, since they are different from both standard Java
   * objects and collection-like objects.
   */
  public int shallowHashCode() {
    return isSingleton() || children instanceof ListenableFuture
        ? Objects.hash(getOrder(), children)
        : Objects.hash(getOrder(), Arrays.hashCode((Object[]) children));
  }

  @VisibleForTesting static final int MAX_ELEMENTS_TO_STRING = 1_000_000;

  @Override
  public String toString() {
    if (isSingleton(children)) {
      return "[" + children + "]";
    }
    if (children instanceof Future && !((Future<Object[]>) children).isDone()) {
      return "Deserializing NestedSet with future: " + children;
    }
    ImmutableList<?> elems = toList();
    if (elems.size() <= MAX_ELEMENTS_TO_STRING) {
      return elems.toString();
    }
    return elems.subList(0, MAX_ELEMENTS_TO_STRING)
        + " (truncated, full size "
        + elems.size()
        + ")";
  }

  /**
   * Implementation of {@link #toList}. Uses one of three strategies based on the value of {@code
   * this.memo}: wrap our direct items in a list, call {@link #lockedExpand} to perform the initial
   * {@link #walk}, or call {@link #replay} if we have a nontrivial memo.
   */
  private ImmutableList<E> expand(Object[] children) {
    // This value is only set in the constructor, so safe to test here with no lock.
    if (memo == LEAF_MEMO) {
      return ImmutableList.copyOf(new ArraySharingCollection<>(children));
    }
    CompactHashSet<E> members = lockedExpand(children);
    if (members != null) {
      return ImmutableList.copyOf(members);
    }
    ImmutableList.Builder<E> output = ImmutableList.builderWithExpectedSize(orderAndSize >> 2);
    replay(output, children, memo, 0);
    return output.build();
  }

  // Hack to share our internal array with ImmutableList/ImmutableSet, or avoid
  // a copy in cases where we can preallocate an array of the correct size.
  private static final class ArraySharingCollection<E> extends AbstractCollection<E> {
    private final Object[] array;
    ArraySharingCollection(Object[] array) {
      this.array = array;
    }

    @Override
    public Object[] toArray() {
      return array;
    }

    @Override
    public int size() {
      return array.length;
    }

    @Override
    public Iterator<E> iterator() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * If this is the first call for this object, fills {@code this.memo} and returns a set from
   * {@link #walk}. Otherwise returns null; the caller should use {@link #replay} instead.
   */
  private synchronized CompactHashSet<E> lockedExpand(Object[] children) {
    if (memo != null) {
      return null;
    }
    CompactHashSet<E> members = CompactHashSet.createWithExpectedSize(128);
    CompactHashSet<Object> sets = CompactHashSet.createWithExpectedSize(128);
    sets.add(children);
    memo = new byte[Math.min((children.length + 7) / 8, 8)];
    int pos =
        walk(
            sets,
            members,
            children,
            /* pos= */ 0,
            /* currentDepth= */ 1,
            expansionDepthLimit.get());
    int bytes = (pos + 7) / 8;
    if (bytes <= memo.length - 16) {
      memo = Arrays.copyOf(memo, bytes);
    }
    Preconditions.checkState(members.size() < (Integer.MAX_VALUE >> 2));
    orderAndSize |= (members.size()) << 2;
    return members;
  }

  /**
   * Perform a depth-first traversal of {@code children}, tracking visited arrays in {@code sets}
   * and visited leaves in {@code members}. We also record which edges were taken in {@code
   * this.memo} starting at {@code pos}.
   *
   * <p>Returns the final value of {@code pos}.
   */
  private int walk(
      CompactHashSet<Object> sets,
      CompactHashSet<E> members,
      Object[] children,
      int pos,
      int currentDepth,
      int maxDepth) {
    if (currentDepth > maxDepth) {
      throw new NestedSetDepthException(maxDepth);
    }
    for (Object child : children) {
      if ((pos >> 3) >= memo.length) {
        memo = Arrays.copyOf(memo, memo.length * 2);
      }
      if (child instanceof Object[]) {
        if (sets.add(child)) {
          int prepos = pos;
          int presize = members.size();
          pos = walk(sets, members, (Object[]) child, pos + 1, currentDepth + 1, maxDepth);
          if (presize < members.size()) {
            memo[prepos >> 3] |= (byte) (1 << (prepos & 7));
          } else {
            // We didn't find any new nodes, so don't mark this branch as taken.
            // Rewind pos.  The rest of the array is still zeros because no one
            // deeper in the traversal set any bits.
            pos = prepos + 1;
          }
        } else {
          ++pos;
        }
      } else {
        if (members.add((E) child)) {
          memo[pos >> 3] |= (byte) (1 << (pos & 7));
        }
        ++pos;
      }
    }
    return pos;
  }

  /**
   * Repeat a previous traversal of {@code children} performed by {@link #walk} and recorded in
   * {@code memo}, appending leaves to {@code output}.
   */
  private static <E> int replay(
      ImmutableList.Builder<E> output, Object[] children, byte[] memo, int pos) {
    for (Object child : children) {
      if ((memo[pos >> 3] & (1 << (pos & 7))) != 0) {
        if (child instanceof Object[]) {
          pos = replay(output, (Object[]) child, memo, pos + 1);
        } else {
          output.add((E) child);
          ++pos;
        }
      } else {
        ++pos;
      }
    }
    return pos;
  }

  /**
   * Sets the application depth limit of nested sets. When flattening a {@link NestedSet} deeper
   * than this limit, a {@link NestedSetDepthException} will be thrown.
   *
   * <p>This limit should be set by command line option processing.
   *
   * @return true if the previous limit was different than this new limit
   */
  public static boolean setApplicationDepthLimit(int newLimit) {
    int oldValue = expansionDepthLimit.getAndSet(newLimit);
    return oldValue != newLimit;
  }

  /** An exception thrown when a nested set exceeds the application's depth limits. */
  public static final class NestedSetDepthException extends RuntimeException {
    private final int depthLimit;

    public NestedSetDepthException(int depthLimit) {
      this.depthLimit = depthLimit;
    }

    /** Returns the depth limit that was exceeded which resulted in this exception being thrown. */
    public int getDepthLimit() {
      return depthLimit;
    }
  }

  /**
   * Returns a new NestedSet containing the same elements, but represented using a logical graph
   * with the specified maximum out-degree, which must be at least 2. The resulting set's iteration
   * order is undefined.
   */
  public NestedSet<E> splitIfExceedsMaximumSize(int maximumSize) {
    Preconditions.checkArgument(maximumSize >= 2, "maximumSize must be at least 2");
    Object children = getChildren(); // may wait for a future
    if (!(children instanceof Object[])) {
      return this;
    }
    Object[] arr = (Object[]) children;
    int size = arr.length;
    if (size <= maximumSize) {
      return this;
    }
    Object[][] pieces = new Object[((size + maximumSize - 1) / maximumSize)][];
    for (int i = 0; i < pieces.length; i++) {
      int max = Math.min((i + 1) * maximumSize, arr.length);
      pieces[i] = Arrays.copyOfRange(arr, i * maximumSize, max);
    }

    // TODO(adonovan): why is this recursive call here? It looks like it is intended to propagate
    // the split down to each subgraph, but it clearly never did that, because it would have to be
    // applied to each element of pieces[i]. More surprising is that it does anything at all: pieces
    // has already been split. Yet a test breaks if it is removed. Investigate and simplify.
    return new NestedSet<E>(getOrder(), pieces, null).splitIfExceedsMaximumSize(maximumSize);
  }

  /** Returns the list of this node's successors that are themselves non-leaf nodes. */
  public ImmutableList<NestedSet<E>> getNonLeaves() {
    Object children = getChildren(); // may wait for a future
    if (!(children instanceof Object[])) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<NestedSet<E>> res = ImmutableList.builder();
    for (Object c : (Object[]) children) {
      if (c instanceof Object[]) {
        res.add(new NestedSet<>(getOrder(), c, null));
      }
    }
    return res.build();
  }

  /**
   * Returns the list of elements (leaf nodes) of this set that are reached by following at most one
   * graph edge.
   */
  @SuppressWarnings("unchecked")
  public ImmutableList<E> getLeaves() {
    Object children = getChildren(); // may wait for a future
    if (!(children instanceof Object[])) {
      return ImmutableList.of((E) children);
    }
    ImmutableList.Builder<E> res = ImmutableList.builder();
    for (Object c : (Object[]) children) {
      if (!(c instanceof Object[])) {
        res.add((E) c);
      }
    }
    return res.build();
  }

  /**
   * Returns a Node, an opaque reference to the logical node of the DAG that this NestedSet
   * represents.
   */
  public Node toNode() {
    return new Node(children);
  }

  /**
   * A Node is an opaque reference to a logical node of the NestedSet DAG.
   *
   * <p>The only operation it supports is {@link Object#equals}. Branch nodes are equal if and only
   * if they refer to the same logical graph node. Leaf nodes are equal if they refer to equal
   * elements. Two distinct NestedSets may have equal elements.
   *
   * <p>Node is provided so that clients can implement their own traversals and detect when they
   * have encountered a subgraph already visited.
   */
  public static class Node {
    private final Object children;

    private Node(Object children) {
      this.children = children;
    }

    @Override
    public int hashCode() {
      return children.hashCode();
    }

    @Override
    public boolean equals(Object that) {
      return that instanceof Node && this.children.equals(((Node) that).children);
    }

    @Override
    public String toString() {
      return "NestedSet.Node@" + hashCode(); // intentionally opaque
    }
  }
}
