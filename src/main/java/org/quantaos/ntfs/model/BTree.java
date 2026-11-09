package org.quantaos.ntfs.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;

/**
 * Пункт 1.1.6 :регистронезависимый индекс имён каталога на B-дереве
 *
 * <p>В {@link #put(Object, Object)} реализована вставка с упреждающим делением
 * полного узла. Метод {@link #find(Object)} выполняет бинарный поиск в каждом
 * узле. При удалении дерево пересобирается из оставшихся ключей: это сохраняет
 * инварианты B-дерева и упрощает учебную реализацию в рамках КП.</p>
 */

public final class BTree<K, V> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int t;

    private final Comparator<? super K> comparator;

    private Node<K, V> root;

    private int size;

    /**
     * Создаёт пустое B-дерево.
     *
     * @param t минимальная степень дерева; максимум ключей в
     * узле равен {@code 2 * t - 1}
     * @param comparator правило упорядочивания ключей
     */
    public BTree(int t, Comparator<? super K> comparator) {
        if (t < 2)
            throw new IllegalArgumentException("Минимальная степень B-дерева должна быть не меньше 2");
        this.t = t;
        this.comparator = comparator;
        this.root = new Node<>(true);
    }
    /**
     * Ищет ключ от корня к листу, выбирая потомка двоичным поиском.
     * Это алгоритм BTREE_FIND из пункта 1.6.
     *
     * @param key искомый ключ
     * @return связанное значение или пустой результат, если ключа нет
     */
    public Optional<V> find(K key) {
        Node<K, V> n = root;
        while (true) {
            int i = index(n, key);
            if (i < n.keys.size() && cmp(key, n.keys.get(i).key) == 0)
                return Optional.of(n.keys.get(i).value);
            if (n.leaf)
                return Optional.empty();
            n = n.children.get(i);
        }
    }
    /**
     * Добавляет новую пару ключ–значение, сохраняя сбалансированность дерева.
     * Полный корень или потомок делится до спуска в него.
     *
     * @throws IllegalArgumentException если ключ уже существует
     */
    public void put(K key, V value) {
        if (find(key).isPresent())
            throw new IllegalArgumentException("Имя уже существует: " + key);
        if (root.keys.size() == 2 * t - 1) {
            Node<K, V> s = new Node<>(false);
            s.children.add(root);
            split(s, 0);
            root = s;
        }
        insertNonFull(root, new Entry<>(key, value));
        size++;
    }
    /**
     * Перестроение дерева при удалении сохраняет все инварианты B-дерева и делает реализацию удаления
     * компактной для данного курсового проекта
     */
    /**
     * Удаляет ключ. Для компактности учебной реализации дерево строится заново из
     * оставшихся ключей, поэтому все ограничения на число ключей в узле
     * сохраняются.
     *
     * @return {@code true}, если ключ действительно был удалён
     */
    public boolean remove(K key) {
        List<Entry<K, V>> all = entries();
        boolean removed = all.removeIf(e -> cmp(e.key, key) == 0);
        if (!removed)
            return false;
        root = new Node<>(true);
        size = 0;
        for (Entry<K, V> e : all)
            put(e.key, e.value);
        return true;
    }
    /**
     * Возвращает все записи в отсортированном порядке для вывода каталога или
     * обхода.
     *
     * @return неизменяемый для дерева список пар ключ–значение
     */
    public List<Entry<K, V>> entries() {
        List<Entry<K, V>> out = new ArrayList<>();
        collect(root, out);
        return out;
    }
    /** @return количество ключей во всём B-дереве. */
    public int size() { return size; }

    /**
     * Выполняет симметричный обход поддерева и добавляет ключи по возрастанию.
     */
    private void collect(Node<K, V> n, List<Entry<K, V>> out) {
        for (int i = 0; i < n.keys.size(); i++) {
            if (!n.leaf)
                collect(n.children.get(i), out);
            out.add(n.keys.get(i));
        }
        if (!n.leaf)
            collect(n.children.get(n.keys.size()), out);
    }
    /**
     * Вставляет запись в гарантированно неполный узел, по пути деля полных
     * потомков.
     */
    private void insertNonFull(Node<K, V> n, Entry<K, V> e) {
        int i = index(n, e.key);
        if (n.leaf) {
            n.keys.add(i, e);
            return;
        }
        if (n.children.get(i).keys.size() == 2 * t - 1) {
            split(n, i);
            int c = cmp(e.key, n.keys.get(i).key);
            if (c > 0)
                i++;
        }
        insertNonFull(n.children.get(i), e);
    }
    /**
     * Делит полного потомка родителя на два узла и поднимает средний ключ вверх.
     * Это процедура «ДЕЛЕНИЕ» из алгоритма BTREE_INSERT.
     */
    private void split(Node<K, V> parent, int childIndex) {
        Node<K, V> y = parent.children.get(childIndex), z = new Node<>(y.leaf);
        Entry<K, V> middle = y.keys.get(t - 1);
        z.keys.addAll(new ArrayList<>(y.keys.subList(t, y.keys.size())));
        y.keys.subList(t - 1, y.keys.size()).clear();
        if (!y.leaf) {
            z.children.addAll(
                    new ArrayList<>(y.children.subList(t, y.children.size())));
            y.children.subList(t, y.children.size()).clear();
        }
        parent.keys.add(childIndex, middle);
        parent.children.add(childIndex + 1, z);
    }
    /** Находит позицию первого ключа, который не меньше искомого. */
    private int index(Node<K, V> n, K key) {
        int low = 0, high = n.keys.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (cmp(n.keys.get(middle).key, key) < 0)
                low = middle + 1;
            else
                high = middle;
        }
        return low;
    }
    /** Сравнивает два ключа заданным при создании дерева компаратором. */
    private int cmp(K a, K b) { return comparator.compare(a, b); }

    /** Одна пара «имя файла — номер записи MFT» в индексе каталога. */
    public record Entry<K, V>(K key, V value) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }
    /** Внутреннее представление узла B-дерева: ключи и ссылки на потомков. */
    private static final class Node<K, V> implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        final boolean leaf;

        final List<Entry<K, V>> keys = new ArrayList<>();

        final List<Node<K, V>> children = new ArrayList<>();

        /** @param leaf признак листового узла без потомков */
        Node(boolean leaf) { this.leaf = leaf; }
    }
}

